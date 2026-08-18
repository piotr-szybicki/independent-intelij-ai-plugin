package com.github.piotrszybicki.independentintelijaiplugin.aicodingagent

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.Locale

/**
 * Keeps a conversation inside the context window by replacing the output of old tool calls with a
 * one-line note saying what used to be there.
 *
 * Tool output is what a long chat is actually made of. A single `read_project_file` or
 * `find_in_files` is tens of kilobytes, it goes into the history verbatim -- the transcript's 4000
 * character cut is for the screen, not for the request -- and from then on it is re-sent with every
 * later turn. Twenty of them and the conversation is mostly file contents the model read once and
 * has long since acted on.
 *
 * What is dropped is only the result text. The `tool_use` block that asked for it stays exactly as
 * it was, arguments included, so the model can still see what it did and with what -- it is the
 * output that is gone, not the history of the work.
 *
 * ### Why this mutates the history instead of trimming the request
 *
 * The obvious place for this is [AICodingAgentClient.sendMessage], shrinking the messages on their
 * way out and leaving the conversation itself whole. That reads better and costs far more: the
 * elision boundary would move forward every request, so each one would send a prefix that differs
 * from the last somewhere in its middle, and [AICodingAgentClient.withCacheBreakpoints] would have
 * nothing to match against. Every request becomes a cache write at the 1h rate, which for anything
 * short of enormous tool results is more expensive than sending the whole conversation.
 *
 * Editing the history in place keeps the property caching needs: what was elided stays elided, so
 * the prefix only ever grows at its tail. A pass does invalidate everything from the oldest message
 * it touched, which is why [COMPACT_ABOVE] and [COMPACT_DOWN_TO] are far apart -- one pass that
 * frees a fifth of the window, rarely, rather than a little on every turn.
 *
 * The cost of that choice is that the elision is permanent: it reaches what
 * [com.github.piotrszybicki.independentintelijaiplugin.history.ChatHistoryService] saves, so
 * reopening a compacted chat does not bring the output back. The transcript keeps the full text and
 * still shows it -- the model's copy is the one that shrinks, not the user's.
 *
 * ### The second tier
 *
 * Eliding tool output cannot help a window filled by the conversation itself -- long user messages,
 * long answers, a chat that has run for hours without reading much. When a pass finishes still over
 * target, and only then, [Summarizer] is asked for prose describing everything up to the last turn
 * boundary before the protected tail, and that prose replaces those messages outright.
 *
 * It is second for a reason. It costs a request rather than a local computation, it is lossy in the
 * places an agent can least afford -- exact paths, line numbers, the shape of a diff -- and it
 * discards the whole cached prefix, so the next request writes the cache again from scratch. Against
 * a cache read at a tenth of the base rate, a pass takes a few more turns to pay for itself and is
 * a straight loss if the user stops just after one. So it is the fallback for the case tier 1 has
 * no answer to, not the mechanism.
 */
object HistoryCompaction {

    /**
     * How far a pass gets before it stops, as a fraction of the window.
     *
     * Not the window itself: the reply, its thinking, and whatever the next round of tool calls
     * returns all have to fit above whatever is left, and none of them are known when this runs.
     */
    // Internal because the context meter colours on it: the point at which the user's bar changes
    // colour and the point at which this fires are the same fact, and two copies of it would drift.
    internal const val COMPACT_ABOVE = 0.60

    /**
     * What a pass tries to get back down to. Well below [COMPACT_ABOVE] on purpose -- see the note
     * about caching above. Compacting to just under the trigger would fire again a turn or two
     * later and re-write the prefix each time.
     */
    private const val COMPACT_DOWN_TO = 0.40

    /**
     * How many messages at the end of the history are never touched.
     *
     * The model is usually working from what it read in the last few calls, so this is the working
     * set -- eliding it is what turns compaction into the model re-reading everything it just read.
     * Counted in messages rather than turns because a tool loop makes many of them per turn, and it
     * is the recent tool output specifically that has to survive.
     */
    internal const val PROTECTED_TAIL_MESSAGES = 8

    /**
     * Results shorter than this are left alone. Below a few hundred characters the stub is a
     * meaningful share of what it replaces, and the tool that returned "ok" is not the one filling
     * the window.
     */
    private const val MIN_ELIDABLE_CHARS = 400

    /**
     * Characters per token, for estimating how big the conversation is.
     *
     * There is no tokenizer here and the endpoint can be any provider, so this is the standard rough
     * ratio for English and code. It only has to be good enough to decide whether a pass is due:
     * both ends of the decision are fractions of a window that is itself a user-supplied number, and
     * being twenty percent out moves the trigger point rather than breaking anything.
     */
    private const val CHARS_PER_TOKEN = 4

    /**
     * How an elided result opens. Doubles as how a later pass recognises its own work, since the
     * wire format has nowhere to put a marker -- an unknown field on a `tool_result` block is a
     * rejected request, not an ignored one.
     */
    private const val ELIDED_PREFIX = "[older tool output removed"
    /**
     * The fewest messages a summarising pass will trade for a summary.
     *
     * Below this the summary is not obviously smaller than what it replaces, and it is certainly
     * worse: the cost is a round trip and a permanent loss of detail either way, so a pass that
     * would swallow one exchange is not worth making.
     */
    private const val MIN_SUMMARISABLE_MESSAGES = 4

    /**
     * How an inserted summary opens, and how the model is told what it is reading.
     *
     * Said plainly rather than dressed up as something the user wrote, because that is what it is:
     * from here on the model is working from a description of the early conversation and not from
     * the conversation. A model that knows detail was lost asks or re-reads; one that thinks it has
     * the transcript will confidently act on a paraphrase of it.
     */
    const val SUMMARY_PREFIX = "[The earlier part of this conversation was removed to save context. " +
        "What follows is a summary of it, not a transcript -- treat it as a report of what happened, " +
        "and re-read anything you need the exact contents of.]"

    /**
     * The assistant half of the replacement pair. Content-free on purpose: its job is to keep the
     * roles alternating across the splice, and anything it claimed about the work would be a claim
     * the model never actually made.
     */
    private const val SUMMARY_ACK = "Understood. I will continue from that summary and the messages after it."

    /**
     * What the model is asked for when a summary is needed, appended as a final user turn to the
     * part of the history that is about to be replaced.
     *
     * Written for an agent mid-task rather than for a reader of the chat. The things that make a
     * summary usable here are the ones a general "summarise this" would drop first: which files were
     * touched and how, what was decided and rejected, and above all what is still outstanding. It
     * also has to say not to reproduce tool output -- asked to be thorough about a conversation made
     * of file contents, a model will happily quote them back and summarise nothing at all.
     */
    val SUMMARY_REQUEST: String = """
        Summarise this conversation so it can replace the messages above and be worked from directly.
        You are writing for yourself: everything not in the summary is gone from your context.

        Cover, in prose under headings:
        - What the user asked for, including any constraint or preference they stated.
        - What has been established about the code: the files and symbols involved, what they do, and
          how they relate. Name paths exactly.
        - What has already been changed, file by file, and what was tried and abandoned.
        - What is still outstanding, and the immediate next step.
        - Anything the user corrected you on, in their terms.

        Do not reproduce file contents, command output or diffs -- describe them. Do not address the
        user, open with a preamble, or call any tool: reply with the summary and nothing else.
    """.trimIndent()

    /**
     * Produces the prose that stands in for the messages handed to it, or null when it could not --
     * a failed request, or a reply with no text in it. Implemented by the agent loop, which is what
     * has an endpoint to ask.
     */
    fun interface Summarizer {
        fun summarize(messages: List<ChatMessage>): String?
    }


    /** What one pass did. */
    data class Result(
        /** How many `tool_result` blocks were replaced. */
        val evicted: Int,
        val beforeTokens: Int,
        val afterTokens: Int,
        /**
         * How many messages a summarising pass traded for a summary, zero when it did not run.
         *
         * Worth reporting separately from [evicted]: eliding drops output the model had already
         * acted on, while this drops the conversation itself, and only one of the two is worth
         * telling the user about in those terms.
         */
        val summarizedMessages: Int = 0,
    ) {
        val freedTokens: Int get() = beforeTokens - afterTokens
        val summarized: Boolean get() = summarizedMessages > 0
        val isEmpty: Boolean get() = evicted == 0 && summarizedMessages == 0
    }

    /**
     * Elides old tool output in [history] until the estimate is back under target, or until the
     * protected tail is reached and there is nothing left it is willing to drop.
     *
     * [contextWindowTokens] is the model's window; zero or less turns compaction off entirely, which
     * is the escape hatch for an endpoint whose window this cannot know. [overheadTokens] is what
     * the request carries besides the messages -- the system prompt and the tool schemas, which
     * together are a substantial and entirely fixed share of it.
     *
     * [summarizer], when given, is the second tier: it only runs when eliding tool output has
     * finished and the conversation is *still* over target, which is the case eliding cannot help --
     * a window filled with what the user and the model wrote rather than with what the tools
     * returned. Leaving it null keeps this a purely local computation.
     *
     * Safe to call before every request: below the trigger it estimates the size and returns.
     */
    fun compact(
        history: MutableList<ChatMessage>,
        contextWindowTokens: Int,
        overheadChars: Int = 0,
        // What a character is worth in this conversation. Left at the standing guess by default and
        // given [ContextMeter]'s calibrated figure once there is one, so that the gauge the user
        // reads and the threshold this fires on are the same measurement -- a bar sitting at 45%
        // when compaction runs at its 60% mark looks like a bug in whichever of the two is right.
        tokensPerChar: Double = ContextMeter.DEFAULT_TOKENS_PER_CHAR,
        summarizer: Summarizer? = null,
    ): Result {
        var chars = charsOf(history) + overheadChars.coerceAtLeast(0)
        val before = tokensIn(chars, tokensPerChar)
        if (contextWindowTokens <= 0) return Result(0, before, before)

        // The window converted into characters once, rather than the history converted into tokens
        // on every comparison below: the history is what changes as the pass runs.
        val windowChars = contextWindowTokens / tokensPerChar
        val triggerChars = windowChars * COMPACT_ABOVE
        if (chars < triggerChars) return Result(0, before, before)

        val targetChars = windowChars * COMPACT_DOWN_TO
        val elidable = (history.size - PROTECTED_TAIL_MESSAGES).coerceAtLeast(0)

        // Oldest first: the earliest output is the least likely to still be what the model is
        // working from, and going in this order is what leaves the newest prefix untouched.
        var evicted = 0
        for (i in 0 until elidable) {
            if (chars <= targetChars) break
            val message = history[i]
            // Results only ever arrive on a user message, and only ever in the one right after the
            // assistant turn that asked for them.
            if (message.role != "user") continue
            val elision = elide(message, history.getOrNull(i - 1)) ?: continue
            history[i] = elision.message
            // Subtracted rather than re-measured, so a pass is one walk of the history instead of
            // one per message elided. It undercounts a little -- what was measured is the escaped
            // JSON and what is subtracted is the raw text -- which errs towards eliding one message
            // more than needed, the harmless direction.
            chars -= elision.savedChars
            evicted += elision.count
        }

        val summarized = if (chars > targetChars && summarizer != null) summarize(history, summarizer) else 0
        if (summarized > 0) chars = charsOf(history) + overheadChars.coerceAtLeast(0)

        return Result(evicted, before, tokensIn(chars, tokensPerChar), summarized)
    }

    private fun tokensIn(chars: Int, tokensPerChar: Double): Int =
        (chars.coerceAtLeast(0) * tokensPerChar).toInt()

    /**
     * Replaces the oldest part of [history] with prose from [summarizer], and returns how many
     * messages that traded away -- zero when nothing was done.
     *
     * Failure is a no-op rather than an error. The summary is one more request to the same endpoint
     * that just succeeded or is about to, so it can time out, be refused, or come back with no text
     * in it at all; when it does, the history is left exactly as tier 1 finished with it and the
     * turn goes out oversized. That is a request the provider may well reject, but it is the same
     * outcome as not having tier 2, and it beats a conversation truncated on the strength of a
     * summary that never arrived.
     *
     * What replaces the messages is a *pair* -- the summary as a user turn, then a one-line
     * assistant acknowledgement. A lone user message would leave the kept half starting with
     * another user turn, and the roles have to keep alternating across the splice.
     */
    private fun summarize(history: MutableList<ChatMessage>, summarizer: Summarizer): Int {
        val cut = summaryBoundary(history)
        if (cut <= 0) return 0

        val summary = runCatching { summarizer.summarize(history.subList(0, cut).toList()) }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return 0

        history.subList(0, cut).clear()
        history.add(0, ChatMessage.text("assistant", SUMMARY_ACK))
        history.add(0, ChatMessage.text("user", "$SUMMARY_PREFIX\n\n$summary"))
        return cut
    }

    /** Roughly how much of the window [messages] takes up. */
    fun estimateTokens(messages: List<ChatMessage>): Int = charsOf(messages) / CHARS_PER_TOKEN

    /**
     * Roughly what the request carries besides the conversation.
     *
     * Worth counting rather than ignoring: some thirty tool schemas and the system prompt are tens
     * of thousands of tokens on every request, and leaving them out would have compaction hold off
     * until well past the point the window actually runs out.
     */
    fun overheadTokens(system: String?, tools: List<ToolDefinition>): Int =
        overheadChars(system, tools) / CHARS_PER_TOKEN

    /**
     * The fixed part of every request -- system prompt and tool schemas -- in characters.
     *
     * Characters rather than tokens because that is what [compact] and [ContextMeter] both weigh,
     * and rounding to tokens here would mean rounding at the default ratio and then correcting a
     * figure that has already lost its precision.
     */
    fun overheadChars(system: String?, tools: List<ToolDefinition>): Int {
        var chars = system?.length ?: 0
        for (tool in tools) {
            chars += tool.name.length + tool.description.length + tool.input_schema.toString().length
        }
        return chars
    }

    /**
     * The serialised size of [messages].
     *
     * Rebuilt on every call rather than tracked as the history grows. It is a string the size of the
     * request that is about to be sent anyway, next to a network round trip, and a cached figure
     * that drifted out of step with a list the agent loop mutates in place would be the harder thing
     * to get right.
     */
    internal fun charsOf(messages: List<ChatMessage>): Int = messages.sumOf { it.content.toString().length }

    /**
     * The index a summarising pass may cut at: everything before it is summarised away, everything
     * from it on is kept verbatim. Zero when there is nowhere safe to cut, which is the answer a
     * young or tool-heavy history gives and the reason tier 2 can decline to run at all.
     *
     * The cut has to land on a real user turn -- a `user` message carrying no `tool_result` -- and
     * that is what makes the result sendable. Cutting anywhere else leaves the kept half starting
     * with results whose `tool_use` was just deleted, which every provider rejects, and it puts the
     * summary between an assistant turn and the answers it was waiting for. A turn boundary has
     * neither problem: the roles still alternate across the splice, and no pair is split.
     *
     * Searched backwards from the protected tail, so the cut is the latest one that qualifies and
     * the summary swallows as much as it can. Going forwards would find the first turn boundary in
     * the conversation and summarise two messages.
     */
    private fun summaryBoundary(history: List<ChatMessage>): Int {
        for (i in history.size - PROTECTED_TAIL_MESSAGES downTo MIN_SUMMARISABLE_MESSAGES) {
            if (isUserTurn(history[i])) return i
        }
        return 0
    }

    /**
     * Whether [message] is something the user actually typed, as opposed to the tool results the
     * loop sends back under the same role.
     */
    private fun isUserTurn(message: ChatMessage): Boolean {
        if (message.role != "user") return false
        return message.content.none {
            it.isJsonObject && it.asJsonObject.get("type")?.asString == "tool_result"
        }
    }

    private class Elision(val message: ChatMessage, val savedChars: Int, val count: Int)

    /**
     * A copy of [message] with its tool output replaced by stubs, or null when it had nothing worth
     * replacing -- in which case the original is left in the list untouched, references and all.
     *
     * [previous] is the message before it, which is where the name of the tool each result belongs
     * to is found.
     */
    private fun elide(message: ChatMessage, previous: ChatMessage?): Elision? {
        var saved = 0
        var count = 0
        val copy = JsonArray()

        for (block in message.content) {
            if (!block.isJsonObject) {
                copy.add(block)
                continue
            }
            val obj = block.asJsonObject
            if (obj.get("type")?.asString != "tool_result") {
                copy.add(block)
                continue
            }
            val original = obj.get("content")?.takeIf { it.isJsonPrimitive }?.asString
            if (original == null || original.length < MIN_ELIDABLE_CHARS || original.startsWith(ELIDED_PREFIX)) {
                copy.add(block)
                continue
            }

            val id = obj.get("tool_use_id")?.asString.orEmpty()
            val stub = stubFor(original, toolNameOf(previous, id))
            // Rebuilt rather than copied and edited: a `tool_result` is these three fields, and
            // building it here means nothing else the block was carrying survives into the request.
            copy.add(JsonObject().apply {
                addProperty("type", "tool_result")
                addProperty("tool_use_id", id)
                addProperty("content", stub)
            })
            saved += original.length - stub.length
            count++
        }

        return if (count == 0) null else Elision(ChatMessage(message.role, copy), saved, count)
    }

    /**
     * What stands in for a dropped result.
     *
     * Deliberately says nothing about calling the tool again. Half the catalog writes rather than
     * reads -- `edit_file_lines`, `create_file`, `run_shell_command` -- and a stub that invites a
     * retry is inviting the model to re-run an edit whose line numbers have since moved, or a shell
     * command that was never idempotent. It states what happened and leaves the decision where it
     * belongs: a model that needs a file again will read it again, and one that does not, will not.
     */
    private fun stubFor(original: String, toolName: String): String {
        val kb = original.toByteArray(Charsets.UTF_8).size / 1024.0
        // Locale.ROOT, or this renders as "12,4 kB" wherever the decimal separator is a comma.
        return "$ELIDED_PREFIX to save context: %.1f kB from %s. The call ran; its output is no longer part of this conversation.]"
            .format(Locale.ROOT, kb, toolName)
    }

    /** The name of the tool [id] was a call to, from the assistant turn that asked for it. */
    private fun toolNameOf(assistantMessage: ChatMessage?, id: String): String {
        if (assistantMessage == null || assistantMessage.role != "assistant" || id.isEmpty()) return "a tool"
        for (block in assistantMessage.content) {
            if (!block.isJsonObject) continue
            val obj = block.asJsonObject
            if (obj.get("type")?.asString != "tool_use") continue
            if (obj.get("id")?.asString != id) continue
            return obj.get("name")?.asString?.takeIf { it.isNotBlank() } ?: "a tool"
        }
        return "a tool"
    }
}
