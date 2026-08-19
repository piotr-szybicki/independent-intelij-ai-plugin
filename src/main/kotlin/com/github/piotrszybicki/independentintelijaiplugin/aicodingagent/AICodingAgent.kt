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
            first and read only the ranges it points at. Each declaration comes back with the
            span it covers -- start-end for anything spanning several lines, a single number
            when it fits on one -- and that range is what read_project_file and edit_file_lines
            take, so a method can be read on its own. Read a file whole only when the outline
            shows it is small, or when what you need is code the outline does not name.
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

    private fun systemPrompt(available: List<AICodingAgentTool>): String {
        val names = available.mapTo(mutableSetOf()) { it.name }
        val parts = mutableListOf(basePrompt)
        commentRule(names)?.let { parts.add(it) }
        runCatching { skills() }.getOrDefault("").trim().takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        return parts.joinToString("\n\n")
    }

    private fun commentRule(enabled: Set<String>): String? {
        val writes = "insert_comment" in enabled
        val reads = "get_comment" in enabled
        if (!writes && !reads) return null

        val rules = mutableListOf<String>()
        if (writes) {
            rules.add(
                "Documentation comments are kept in a database rather than in the files. When what " +
                    "you are writing would be a Javadoc or KDoc block -- anything of the /** ... */ " +
                    "form -- call insert_comment with the comment text and write the " +
                    "\"// comment_id: <id>\" line it gives you back at the place that comment " +
                    "belongs. Never write a /** ... */ block into a file. Comments starting with // " +
                    "are written into the code as normal.",
            )
        }
        if (reads) {
            rules.add(
                "A \"// comment_id: N\" line in a file is a stored comment, and is the documentation " +
                    "for whatever follows it. Call get_comment with N to read it -- the code around " +
                    "it may make no sense without what it says.",
            )
        }
        return rules.joinToString("\n\n")
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
        // The conversation's meter, not the turn's: what it has learned about this history and this
        // model is worth keeping between turns, and a fresh one every turn would spend the first
        // request of each guessing at what it already knew.
        meter: ContextMeter = ContextMeter(),
    ) {
        // Once for the whole turn, not once per iteration: connecting to an MCP server is not free,
        // and a tool list that changed underneath the loop would leave the model calling a tool
        // that no longer exists.
        val available = tools()
        val toolsByName = available.associateBy { it.name }
        val toolDefinitions = available.map { it.toDefinition() }
        // Same reasoning as the tool list: resolved once for the whole turn, so the system prompt
        // cannot change underneath the loop between one iteration and the next.
        val system = systemPrompt(available)
        // Both halves are fixed for the turn, so what they cost is measured once rather than on
        // every iteration -- and it is not small: thirty tool schemas are most of a short request.
        val overheadChars = HistoryCompaction.overheadChars(system, toolDefinitions)

        // Measured on demand rather than tracked, because every caller of this already holds the
        // history it wants measured and the meter costs one walk of it.
        fun reportContext() = listener.onContext(meter.estimate(history, overheadChars), contextWindowTokens)

        // A budget rather than a fixed count: the cap is there to stop a runaway loop, not to end a
        // long piece of work, and only the user can tell the two apart. When it runs out the loop
        // stops and asks, and an answer of yes buys another [maxIterations].
        var budget = maxIterations
        var used = 0

        // Climbs rather than staying put: each yes to [Listener.onMaxTokens] doubles it, so a
        // conversation that keeps overrunning stops being interrupted every thousand tokens.
        // [maxTokens] itself is taken as given -- the ceiling bounds what doubling adds, not what
        // the user asked for.
        var tokenCap = maxTokens

        // The finally is what keeps a failed turn from costing the whole conversation: see
        // [answerDanglingToolUse].
        try {
            while (true) {
                if (used >= budget) {
                    log.info("AICodingAgent reached the $budget tool-call iteration cap")
                    if (!listener.onMaxIterations(used)) return
                    budget += maxIterations
                }
                used++
                // Before the request rather than after the response: what has to fit is what is
                // about to be sent. And once per iteration rather than once per turn, because a
                // long tool loop can add more to the history in one turn than the user did in the
                // whole conversation before it -- the window runs out mid-turn or not at all.
                //
                // The summarizer is passed on every call but asked for nothing until eliding tool
                // output has failed to get the conversation under target; see [HistoryCompaction].
                val summarizer = HistoryCompaction.Summarizer { messages ->
                    // A cancel between the decision to summarise and the request for it: the pass
                    // reads null as "could not", leaves the history alone, and the loop stops at the
                    // check below without having spent anything.
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
                        // The messages the last response was counted against have just been
                        // rewritten, so what the provider said about them no longer describes what
                        // is there -- and it is too high by exactly the room this pass made.
                        meter.invalidateAnchor()
                        listener.onCompacted(it)
                    }
                reportContext()

                if (isCancelled()) return
                // What the prompt about to go out is made of, kept for the calibration below: after
                // the response lands the history has grown by the reply, and the two figures the
                // meter needs are measured either side of that.
                val sentMessages = history.size
                val sentChars = HistoryCompaction.charsOf(history) + overheadChars
                val turn = AICodingAgentClient.sendMessage(
                    endpoint, model, tokenCap, history, toolDefinitions, system, reasoning, conversationId,
                )
                turn.usage?.let(listener::onUsage)
                val truncated = turn.stopReason == "max_tokens"
                val content = if (truncated) withoutTrailingToolUse(turn.content) else turn.content
                // Whether the limit landed mid-tool-call rather than mid-sentence, which is the
                // difference between a reply to be continued and a call to be made again.
                val droppedToolUse = content.size() < turn.content.size()
                if (content.size() > 0) {
                    history.add(ChatMessage("assistant", content))
                }
                // After the reply reaches the history, because the reply is part of the next
                // request: what the meter anchors on is the conversation the next prompt starts
                // from, not the one this prompt was.
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
                        // Empty unless the request asked for the summary, and empty is the default:
                        // the models return the block either way and blank the text when not asked.
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
                    // The answer stopped mid-sentence. Only the user can say whether it is worth
                    // another round trip, so ask before spending one -- and offer the continuation
                    // twice the room, since one cut-off reply predicts the next.
                    val suggested =
                        if (tokenCap >= MAX_TOKENS_CEILING) tokenCap
                        else (tokenCap * 2).coerceAtMost(MAX_TOKENS_CEILING)
                    // A cap of zero or less would be a request the API rejects, so it is read as a
                    // stop rather than sent.
                    val next = listener.onMaxTokens(tokenCap, suggested)?.takeIf { it > 0 } ?: return
                    tokenCap = next
                    history.add(
                        ChatMessage.text("user", if (droppedToolUse) RETRY_TOOL_CALL_PROMPT else CONTINUE_PROMPT)
                    )
                    continue
                }

                val toolResults = JsonArray()
                // The calls that overran the limit, in the order they ran. A list rather than the
                // first one found, because the round is run to the end regardless: see the note on
                // the limit in this function's docs.
                val overruns = mutableListOf<Overrun>()
                for ((ordinal, block) in toolUseBlocks.withIndex()) {
                    val obj = block.asJsonObject
                    val toolName = obj.get("name")?.asString.orEmpty()
                    val toolUseId = obj.get("id")?.asString.orEmpty()
                    val input = obj.getAsJsonObject("input") ?: JsonObject()
                    val call = ToolCallId(turn.requestId, toolUseId)

                    val startedAt = System.currentTimeMillis()
                    // Cancelling stops the work but still answers the block: an assistant turn whose
                    // tool_use has no matching tool_result is rejected by the API on the next message.
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
                            // A cancel usually reaches a running tool as an interrupt, so the throw
                            // is how it fails -- reporting that as a failure would blame the user's
                            // Stop on the tool.
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

                    // Counted once and used twice: the limit below decides on it, and the row
                    // recorded further down reports it. Tokenizing a large result is not free, and
                    // doing it a second time for the log would be paying for the same answer twice.
                    val resultTokens = TokenCounter.count(resultText)
                    // Only what a tool actually produced is weighed against the limit. The three
                    // stand-in texts above are a sentence each and are the loop's own words, so
                    // stopping on one would be stopping on nothing -- and a cancelled turn must not
                    // also report an overrun.
                    val produced = outcome == ToolOutcome.OK || outcome == ToolOutcome.FAILED
                    if (maxToolOutputTokens > 0 && produced && resultTokens > maxToolOutputTokens) {
                        log.info(
                            "Tool '$toolName' returned $resultTokens tokens, over the " +
                                "$maxToolOutputTokens-token limit: withholding it",
                        )
                        outcome = ToolOutcome.TOO_LARGE
                        overruns += Overrun(toolName, toolUseId, resultText, resultTokens)
                    }

                    // The real output, whatever is about to be sent in its place: the transcript is
                    // the user's copy and the point of stopping is that they can go and read it.
                    listener.onToolCall(call, toolName, input, resultText, outcome)

                    // The same output again, to the request's own row. A no-op when no usage
                    // database is configured, which is the usual case -- see [ModelUsageDatabase].
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
                // A round of tool results can be most of what a turn costs, and the loop may go
                // round many times before the next response corrects the figure -- so the meter is
                // told here rather than left to catch up.
                reportContext()
                // After the results reach the history, never before: leaving the round unanswered
                // would be exactly the dangling `tool_use` that kills a conversation for good.
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
            // Logged and swallowed. A summary that could not be had is a turn that goes out
            // oversized, which is the provider's to complain about; failing here would turn it into
            // a turn the user never gets an answer to.
            log.info("Summarising the conversation failed, leaving the history as it is: ${e.message}")
            return null
        }
        // Billed like any other request, so it goes through the same counter -- a pass that shows up
        // as tokens from nowhere is the thing that makes the usage figures untrustworthy.
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
