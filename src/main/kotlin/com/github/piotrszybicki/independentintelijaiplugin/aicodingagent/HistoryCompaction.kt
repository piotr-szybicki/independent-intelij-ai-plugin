package com.github.piotrszybicki.independentintelijaiplugin.aicodingagent

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.Locale

object HistoryCompaction {

    internal const val COMPACT_ABOVE = 0.60

    private const val COMPACT_DOWN_TO = 0.40

    internal const val PROTECTED_TAIL_MESSAGES = 8

    private const val MIN_ELIDABLE_CHARS = 400

    private const val CHARS_PER_TOKEN = 4

    private const val ELIDED_PREFIX = "[older tool output removed"
    private const val MIN_SUMMARISABLE_MESSAGES = 4

    const val SUMMARY_PREFIX = "[The earlier part of this conversation was removed to save context. " +
        "What follows is a summary of it, not a transcript -- treat it as a report of what happened, " +
        "and re-read anything you need the exact contents of.]"

    private const val SUMMARY_ACK = "Understood. I will continue from that summary and the messages after it."

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

    fun interface Summarizer {
        fun summarize(messages: List<ChatMessage>): String?
    }


    data class Result(
        val evicted: Int,
        val beforeTokens: Int,
        val afterTokens: Int,
        val summarizedMessages: Int = 0,
    ) {
        val freedTokens: Int get() = beforeTokens - afterTokens
        val summarized: Boolean get() = summarizedMessages > 0
        val isEmpty: Boolean get() = evicted == 0 && summarizedMessages == 0
    }

    fun compact(
        history: MutableList<ChatMessage>,
        contextWindowTokens: Int,
        overheadChars: Int = 0,
        tokensPerChar: Double = ContextMeter.DEFAULT_TOKENS_PER_CHAR,
        summarizer: Summarizer? = null,
    ): Result {
        var chars = charsOf(history) + overheadChars.coerceAtLeast(0)
        val before = tokensIn(chars, tokensPerChar)
        if (contextWindowTokens <= 0) return Result(0, before, before)

        val windowChars = contextWindowTokens / tokensPerChar
        val triggerChars = windowChars * COMPACT_ABOVE
        if (chars < triggerChars) return Result(0, before, before)

        val targetChars = windowChars * COMPACT_DOWN_TO
        val elidable = (history.size - PROTECTED_TAIL_MESSAGES).coerceAtLeast(0)

        var evicted = 0
        for (i in 0 until elidable) {
            if (chars <= targetChars) break
            val message = history[i]
            if (message.role != "user") continue
            val elision = elide(message, history.getOrNull(i - 1)) ?: continue
            history[i] = elision.message
            chars -= elision.savedChars
            evicted += elision.count
        }

        val summarized = if (chars > targetChars && summarizer != null) summarize(history, summarizer) else 0
        if (summarized > 0) chars = charsOf(history) + overheadChars.coerceAtLeast(0)

        return Result(evicted, before, tokensIn(chars, tokensPerChar), summarized)
    }

    private fun tokensIn(chars: Int, tokensPerChar: Double): Int =
        (chars.coerceAtLeast(0) * tokensPerChar).toInt()

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

    fun estimateTokens(messages: List<ChatMessage>): Int = charsOf(messages) / CHARS_PER_TOKEN

    fun overheadTokens(system: String?, tools: List<ToolDefinition>): Int =
        overheadChars(system, tools) / CHARS_PER_TOKEN

    fun overheadChars(system: String?, tools: List<ToolDefinition>): Int {
        var chars = system?.length ?: 0
        for (tool in tools) {
            chars += tool.name.length + tool.description.length + tool.input_schema.toString().length
        }
        return chars
    }

    internal fun charsOf(messages: List<ChatMessage>): Int = messages.sumOf { it.content.toString().length }

    private fun summaryBoundary(history: List<ChatMessage>): Int {
        for (i in history.size - PROTECTED_TAIL_MESSAGES downTo MIN_SUMMARISABLE_MESSAGES) {
            if (isUserTurn(history[i])) return i
        }
        return 0
    }

    private fun isUserTurn(message: ChatMessage): Boolean {
        if (message.role != "user") return false
        return message.content.none {
            it.isJsonObject && it.asJsonObject.get("type")?.asString == "tool_result"
        }
    }

    private class Elision(val message: ChatMessage, val savedChars: Int, val count: Int)

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

    private fun stubFor(original: String, toolName: String): String {
        val kb = original.toByteArray(Charsets.UTF_8).size / 1024.0
        return "$ELIDED_PREFIX to save context: %.1f kB from %s. The call ran; its output is no longer part of this conversation.]"
            .format(Locale.ROOT, kb, toolName)
    }

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
