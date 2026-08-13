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
 */
object HistoryCompaction {

    /**
     * How far a pass gets before it stops, as a fraction of the window.
     *
     * Not the window itself: the reply, its thinking, and whatever the next round of tool calls
     * returns all have to fit above whatever is left, and none of them are known when this runs.
     */
    private const val COMPACT_ABOVE = 0.60

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

    /** What one pass did. */
    data class Result(
        /** How many `tool_result` blocks were replaced. */
        val evicted: Int,
        val beforeTokens: Int,
        val afterTokens: Int,
    ) {
        val freedTokens: Int get() = beforeTokens - afterTokens
        val isEmpty: Boolean get() = evicted == 0
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
     * Safe to call before every request: below the trigger it estimates the size and returns.
     */
    fun compact(history: MutableList<ChatMessage>, contextWindowTokens: Int, overheadTokens: Int = 0): Result {
        var chars = charsOf(history) + overheadTokens.coerceAtLeast(0) * CHARS_PER_TOKEN
        val before = chars / CHARS_PER_TOKEN
        if (contextWindowTokens <= 0) return Result(0, before, before)

        val triggerChars = contextWindowTokens * COMPACT_ABOVE * CHARS_PER_TOKEN
        if (chars < triggerChars) return Result(0, before, before)

        val targetChars = contextWindowTokens * COMPACT_DOWN_TO * CHARS_PER_TOKEN
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

        return Result(evicted, before, chars / CHARS_PER_TOKEN)
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
    fun overheadTokens(system: String?, tools: List<ToolDefinition>): Int {
        var chars = system?.length ?: 0
        for (tool in tools) {
            chars += tool.name.length + tool.description.length + tool.input_schema.toString().length
        }
        return chars / CHARS_PER_TOKEN
    }

    /**
     * The serialised size of [messages].
     *
     * Rebuilt on every call rather than tracked as the history grows. It is a string the size of the
     * request that is about to be sent anyway, next to a network round trip, and a cached figure
     * that drifted out of step with a list the agent loop mutates in place would be the harder thing
     * to get right.
     */
    private fun charsOf(messages: List<ChatMessage>): Int = messages.sumOf { it.content.toString().length }

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
