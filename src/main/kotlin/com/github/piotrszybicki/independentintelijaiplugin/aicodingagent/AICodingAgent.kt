package com.github.piotrszybicki.independentintelijaiplugin.aicodingagent

import com.github.piotrszybicki.independentintelijaiplugin.logging.ModelUsageDatabase
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger

class AICodingAgent(
    private val tools: () -> List<AICodingAgentTool>,
    private val environment: () -> String = { "" },
    private val skills: () -> String = { "" },
) {

    enum class ToolOutcome { OK, FAILED, CANCELLED, TOO_LARGE }

    data class ToolCallId(val requestId: String, val toolUseId: String)

    interface Listener {
        fun onAssistantText(text: String)

        fun onThinking(summary: String) {}

        fun onToolCall(call: ToolCallId, name: String, input: JsonObject, result: String, outcome: ToolOutcome)

        fun onToolStarted(call: ToolCallId, name: String, input: JsonObject, interruptible: Boolean) {}

        fun onUsage(usage: AICodingAgentUsage) {}

        fun onMaxTokens(limit: Int, suggested: Int): Int?

        fun onMaxIterations(used: Int): Boolean = false

        fun onToolOutputTooLarge(name: String, toolUseId: String, output: String, tokens: Int, limit: Int) {}

        fun onCompacted(result: HistoryCompaction.Result) {}

        fun onContext(usedTokens: Int, windowTokens: Int) {}
    }

    companion object {
        private val SYSTEM_PROMPT = """
            You are a coding assistant inside InteliJ IDE.

            Do the work in the turn you announce it in. If you say you are about to read, edit, run
            or check something, the tool call for it belongs in that same turn -- never end a turn
            on a statement of intent about work you have not done. When something takes several
            steps, take the first one now instead of describing the plan and stopping.

            Outline before you read. For a file you do not already know, call get_file_structure
            first and read only the ranges you need to understand the structure. Each declaration comes back with the
            span it covers -- start-end for anything spanning several lines, a single number
            when it fits on one -- and that range is what read_project_file and edit_file_lines
            take, so a method can be read on its own. Read a file whole only when the outline
            shows it is small, or when what you need is code the outline does not name.

            Use summary tool each time you want to run a build a longer output. 
        """.trimIndent()

        const val MAX_TOKENS_CEILING = 12_000

        private const val SUMMARY_MAX_TOKENS = 4_000

        private const val CANCELLED_RESULT = "The user cancelled this turn before the tool finished."

        private const val ABANDONED_RESULT = "This tool never ran: the turn ended with an error before it started."

        private fun withheldResult(tokens: Int, limit: Int): String =
            "This tool ran, but its output was not sent: it came to $tokens tokens, over the " +
                "$limit-token limit on a single tool result. The user has seen the output in the " +
                "IDE; you have not. Ask for less of it next time -- a narrower path, a line range, " +
                "a stricter filter -- rather than repeating the call as it was."

        private const val CONTINUE_PROMPT =
            "Your previous response was cut off because it hit the output token limit. " +
                "Continue from exactly where you stopped -- do not repeat what you already wrote " +
                "and do not start over."

        private const val RETRY_TOOL_CALL_PROMPT =
            "Your previous response was cut off because it hit the output token limit, part-way " +
                "through a tool call. The call was discarded and never ran. Make it again, picking " +
                "up from any text you had already written -- do not repeat that text."
    }

    private val basePrompt: String by lazy {
        val facts = runCatching { environment() }.getOrDefault("").trim()
        if (facts.isEmpty()) SYSTEM_PROMPT else "$SYSTEM_PROMPT\n\n$facts"
    }

    private fun systemPrompt(agentPrompt: String): String {
        val parts = mutableListOf(basePrompt)
        agentPrompt.trim().takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        runCatching { skills() }.getOrDefault("").trim().takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        return parts.joinToString("\n\n")
    }

    private val log = Logger.getInstance(AICodingAgent::class.java)

    fun run(
        endpoint: AICodingAgentEndpoint,
        model: String,
        maxTokens: Int,
        maxIterations: Int,
        contextWindowTokens: Int,
        history: MutableList<ChatMessage>,
        listener: Listener,
        isCancelled: () -> Boolean = { false },
        reasoning: ReasoningOptions = ReasoningOptions.PROVIDER_DEFAULT,
        conversationId: String = "",
        maxToolOutputTokens: Int = 0,
        meter: ContextMeter = ContextMeter(),
        agentPrompt: String = "",
    ) {
        val available = tools()
        val toolsByName = available.associateBy { it.name }
        val toolDefinitions = available.map { it.toDefinition() }
        val system = systemPrompt(agentPrompt)
        val overheadChars = HistoryCompaction.overheadChars(system, toolDefinitions)

        fun reportContext() = listener.onContext(meter.estimate(history, overheadChars), contextWindowTokens)

        var budget = maxIterations
        var used = 0

        var tokenCap = maxTokens

        try {
            while (true) {
                if (used >= budget) {
                    log.info("AICodingAgent reached the $budget tool-call iteration cap")
                    if (!listener.onMaxIterations(used)) return
                    budget += maxIterations
                }
                used++
                val summarizer = HistoryCompaction.Summarizer { messages ->
                    if (isCancelled()) null
                    else summarize(
                        endpoint, model, messages, toolDefinitions, system, reasoning, listener, conversationId,
                    )
                }
                HistoryCompaction.compact(history, contextWindowTokens, overheadChars, meter.tokensPerChar, summarizer)
                    .takeIf { !it.isEmpty }?.let {
                        log.info(
                            "Compacted the conversation: dropped ${it.evicted} tool result(s), " +
                                "summarised ${it.summarizedMessages} message(s), " +
                                "~${it.beforeTokens} tokens -> ~${it.afterTokens}",
                        )
                        meter.invalidateAnchor()
                        listener.onCompacted(it)
                    }
                reportContext()

                if (isCancelled()) return
                val sentMessages = history.size
                val sentChars = HistoryCompaction.charsOf(history) + overheadChars
                val turn = AICodingAgentClient.sendMessage(
                    endpoint, model, tokenCap, history, toolDefinitions, system, reasoning, conversationId,
                )
                turn.usage?.let(listener::onUsage)
                val truncated = turn.stopReason == "max_tokens"
                val content = if (truncated) withoutTrailingToolUse(turn.content) else turn.content
                val droppedToolUse = content.size() < turn.content.size()
                if (content.size() > 0) {
                    history.add(ChatMessage("assistant", content))
                }
                turn.usage?.let {
                    meter.observe(
                        promptTokens = it.promptTokens,
                        outputTokens = it.output_tokens,
                        promptChars = sentChars,
                        coveredMessages = if (content.size() > 0) sentMessages + 1 else sentMessages,
                    )
                    reportContext()
                }

                for (block in content) {
                    val obj = block.asJsonObject
                    when (obj.get("type")?.asString) {
                        "text" -> listener.onAssistantText(obj.get("text")?.asString.orEmpty())
                        "thinking" -> obj.get("thinking")
                            ?.takeIf { it.isJsonPrimitive }
                            ?.asString
                            ?.takeIf { it.isNotBlank() }
                            ?.let(listener::onThinking)
                    }
                }

                val toolUseBlocks = content.filter { it.asJsonObject.get("type")?.asString == "tool_use" }
                if (toolUseBlocks.isEmpty()) {
                    if (!truncated) return
                    val suggested =
                        if (tokenCap >= MAX_TOKENS_CEILING) tokenCap
                        else (tokenCap * 2).coerceAtMost(MAX_TOKENS_CEILING)
                    val next = listener.onMaxTokens(tokenCap, suggested)?.takeIf { it > 0 } ?: return
                    tokenCap = next
                    history.add(
                        ChatMessage.text("user", if (droppedToolUse) RETRY_TOOL_CALL_PROMPT else CONTINUE_PROMPT)
                    )
                    continue
                }

                val toolResults = JsonArray()
                val overruns = mutableListOf<Overrun>()
                for ((ordinal, block) in toolUseBlocks.withIndex()) {
                    val obj = block.asJsonObject
                    val toolName = obj.get("name")?.asString.orEmpty()
                    val toolUseId = obj.get("id")?.asString.orEmpty()
                    val input = obj.getAsJsonObject("input") ?: JsonObject()
                    val call = ToolCallId(turn.requestId, toolUseId)

                    val startedAt = System.currentTimeMillis()
                    var outcome = ToolOutcome.OK
                    val resultText = if (isCancelled()) {
                        outcome = ToolOutcome.CANCELLED
                        CANCELLED_RESULT
                    } else {
                        listener.onToolStarted(call, toolName, input, toolsByName[toolName]?.interruptible ?: true)
                        runCatching {
                            val tool = toolsByName[toolName] ?: throw AICodingAgentApiException("Unknown tool: $toolName")
                            tool.execute(input)
                        }.getOrElse { e ->
                            log.info("Tool '$toolName' failed: ${e.message}")
                            if (isCancelled()) {
                                outcome = ToolOutcome.CANCELLED
                                CANCELLED_RESULT
                            } else {
                                outcome = ToolOutcome.FAILED
                                "Error: ${e.message}"
                            }
                        }
                    }
                    val elapsed = System.currentTimeMillis() - startedAt

                    val resultTokens = TokenCounter.count(resultText)
                    val produced = outcome == ToolOutcome.OK || outcome == ToolOutcome.FAILED
                    if (maxToolOutputTokens > 0 && produced && resultTokens > maxToolOutputTokens) {
                        log.info(
                            "Tool '$toolName' returned $resultTokens tokens, over the " +
                                "$maxToolOutputTokens-token limit: withholding it",
                        )
                        outcome = ToolOutcome.TOO_LARGE
                        overruns += Overrun(toolName, toolUseId, resultText, resultTokens)
                    }

                    listener.onToolCall(call, toolName, input, resultText, outcome)

                    val arguments = input.toString()
                    ModelUsageDatabase.recordToolCall(
                        conversationId = conversationId,
                        requestId = turn.requestId,
                        ordinal = ordinal,
                        toolUseId = toolUseId,
                        toolName = toolName,
                        arguments = arguments,
                        result = resultText,
                        outcome = outcome.name,
                        argumentTokens = TokenCounter.count(arguments),
                        resultTokens = resultTokens,
                        durationMillis = elapsed,
                    )

                    toolResults.add(JsonObject().apply {
                        addProperty("type", "tool_result")
                        addProperty("tool_use_id", toolUseId)
                        addProperty(
                            "content",
                            if (outcome == ToolOutcome.TOO_LARGE) {
                                withheldResult(resultTokens, maxToolOutputTokens)
                            } else {
                                resultText
                            },
                        )
                    })
                }

                history.add(ChatMessage("user", toolResults))
                reportContext()
                if (overruns.isNotEmpty()) {
                    overruns.forEach {
                        listener.onToolOutputTooLarge(it.name, it.toolUseId, it.output, it.tokens, maxToolOutputTokens)
                    }
                    return
                }
            }
        } finally {
            answerDanglingToolUse(history)
        }
    }
    private class Overrun(val name: String, val toolUseId: String, val output: String, val tokens: Int)

    private fun summarize(
        endpoint: AICodingAgentEndpoint,
        model: String,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        system: String,
        reasoning: ReasoningOptions,
        listener: Listener,
        conversationId: String,
    ): String? {
        log.info("Summarising ${messages.size} message(s) to make room in the context window")
        val request = messages + ChatMessage.text("user", HistoryCompaction.SUMMARY_REQUEST)
        val turn = try {
            AICodingAgentClient.sendMessage(
                endpoint, model, SUMMARY_MAX_TOKENS, request, tools, system, reasoning, conversationId,
            )
        } catch (e: Exception) {
            log.info("Summarising the conversation failed, leaving the history as it is: ${e.message}")
            return null
        }
        turn.usage?.let(listener::onUsage)

        return turn.content
            .filter { it.isJsonObject && it.asJsonObject.get("type")?.asString == "text" }
            .joinToString("\n") { it.asJsonObject.get("text")?.asString.orEmpty() }
            .takeIf { it.isNotBlank() }
    }


    private fun answerDanglingToolUse(history: MutableList<ChatMessage>) {
        val last = history.lastOrNull() ?: return
        if (last.role != "assistant") return

        val unanswered = last.content.filter {
            it.isJsonObject && it.asJsonObject.get("type")?.asString == "tool_use"
        }
        if (unanswered.isEmpty()) return

        val results = JsonArray()
        for (block in unanswered) {
            results.add(JsonObject().apply {
                addProperty("type", "tool_result")
                addProperty("tool_use_id", block.asJsonObject.get("id")?.asString.orEmpty())
                addProperty("content", ABANDONED_RESULT)
            })
        }
        log.info("Answering ${results.size()} tool call(s) left dangling by a turn that did not finish")
        history.add(ChatMessage("user", results))
    }

    private fun withoutTrailingToolUse(content: JsonArray): JsonArray {
        val last = content.lastOrNull()?.asJsonObject ?: return content
        if (last.get("type")?.asString != "tool_use") return content

        val trimmed = JsonArray()
        for (i in 0 until content.size() - 1) trimmed.add(content[i])
        return trimmed
    }
}
