package com.github.piotrszybicki.independentintelijaiplugin.aicodingagent

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger

/**
 * Drives the request/tool-call/tool-result cycle described at
 * https://docs.anthropic.com/en/docs/build-with-claude/tool-use until the model stops asking for tools.
 */
class AICodingAgent(
    /**
     * The tools to offer, asked for once per [run] rather than fixed at construction.
     *
     * A supplier because not every tool is known when the agent is built: the MCP ones only exist
     * once their servers have been connected to, which is network and process work that cannot
     * happen on the EDT where the tool window is assembled. Resolving per turn also means a server
     * added in settings is usable on the next message instead of after a restart.
     */
    private val tools: () -> List<AICodingAgentTool>,
    /**
     * Machine-specific facts appended to the system prompt. A lambda rather than a string because
     * describing the environment reads IDE settings, which must not happen on the EDT -- the agent
     * is constructed there, but [basePrompt] is not resolved until the first request.
     */
    private val environment: () -> String = { "" },
    /**
     * The skill listing to append to the system prompt, resolved once per [run] like [tools].
     *
     * Per turn rather than once, because a skill is a file the user edits while the conversation is
     * open, and one added mid-chat should be usable on the next message. It stays out of the
     * cacheable prefix's way regardless: the text only differs when the skills themselves do, so
     * turn after turn the prefix is byte-identical.
     */
    private val skills: () -> String = { "" },
) {

    /** How a tool call ended, so the caller can mark it as more than "it happened". */
    enum class ToolOutcome { OK, FAILED, CANCELLED }

    interface Listener {
        fun onAssistantText(text: String)

        /**
         * A tool has finished, whatever [outcome] it finished with. Paired with [onToolStarted] for
         * every tool the loop actually ran -- but not only those: a tool skipped by a cancel is
         * reported here without ever having been started.
         */
        fun onToolCall(name: String, input: JsonObject, result: String, outcome: ToolOutcome)

        /**
         * A tool is about to run. [interruptible] is false when stopping the turn would only stop
         * the waiting, leaving the work itself running.
         *
         * [onToolCall] follows for the same tool unless the turn dies on the way there, which
         * leaves anything the callback drew for it hanging -- the caller's to settle.
         */
        fun onToolStarted(name: String, input: JsonObject, interruptible: Boolean) {}

        /**
         * What the request that just came back cost.
         *
         * Once per request, not once per turn: the loop sends a fresh one for every round of tool
         * calls and each is billed on its own, so anything that adds these up has to see them all.
         * A request that failed reports nothing -- the usage block comes with the response, and an
         * error does not carry one.
         */
        fun onUsage(usage: AICodingAgentUsage) {}

        /**
         * Called when a turn was cut off by the model's output limit. [limit] is the cap that was
         * hit; [suggested] is what the loop proposes continuing at -- twice [limit], because a reply
         * that needed more room once will usually need it again, and asking the same question every
         * thousand tokens is worse than spending them. The two are equal once doubling has run into
         * [MAX_TOKENS_CEILING], which is the signal to stop suggesting and let the user say what the
         * cap should be.
         *
         * Return the cap to continue at -- [suggested], or whatever the user chose instead -- or
         * null to end the turn and leave the partial answer. A returned cap is honoured as given,
         * ceiling included: past that point it is an explicit choice, not a default. The caller is
         * what makes it outlast the turn: keep the value and pass it back to the next [run].
         */
        fun onMaxTokens(limit: Int, suggested: Int): Int?

        /**
         * Called when the loop has used its whole tool-call budget with the model still asking for
         * tools. [used] is how many iterations have gone by. Returning true grants another budget's
         * worth of them; returning false ends the turn where it stands.
         */
        fun onMaxIterations(used: Int): Boolean = false
    }

    companion object {
        /**
         * Sent as the request's `system` field on every turn. This is what tells the model it is a
         * coding assistant working inside an IDE rather than a general chat model -- without it the
         * only thing shaping its behaviour is the tool schemas and whatever the user typed.
         */
        private val SYSTEM_PROMPT = """
            You are a coding assistant embedded in a piotr-szybicki IDE, working on the project the user
            currently has open. You have tools to read, search, edit, and refactor its source, to run
            shell commands, and to drive the debugger. Use them rather than asking the user to paste
            code or run commands on your behalf.

            Working in this project:
            - When the user says "this", "here", "the selected code" or names nothing at all, they
              mean whatever is under their cursor. Call `get_editor_context` to find out rather than
              asking them which file -- they are sitting in front of it and have already pointed.
            - Read before you edit. `read_project_file` and `find_in_files` are cheap; guessing at a
              file's contents is not. `read_project_file` takes a line range -- use it once you know
              which lines you want rather than pulling in whole files.
            - `get_file_structure` is how you find out which lines those are. It outlines a file's
              declarations with their line numbers for a fraction of the cost of reading it, so on
              anything but a short file, outline first and then read the range you need.
            - Code from a dependency is readable too: `read_library_class` takes a class name rather
              than a path and returns its source, or its decompiled bytecode when the library ships
              no sources. `get_file_structure` takes the same name via `class_name`, and on a
              decompiled class that is worth doing first -- they run to thousands of lines. Read the
              library rather than guessing at an API you cannot see. Decompiled code carries no
              comments or Javadoc; `attach_library_sources` fetches the real thing, but it asks the
              user and downloads, so reach for it only when that missing detail is what you need.
            - `get_symbol_info` answers "what is this?" in one call. Point it at a call, a type, or
              any name you are unsure of and it returns the declaration -- signature, doc comment and
              source -- including for library and JDK symbols no project file contains. Reach for it
              instead of reading a whole file to find one signature, and before `find_usages` or
              `rename_symbol` when several declarations share a name.
            - `find_implementations` for "who implements this" and "what overrides this".
              `find_usages` will not answer either: a class can implement an interface without ever
              mentioning its methods by name, so it shows up in one and not the other.
            - `git_status`, `git_diff`, `git_log` and `git_blame` answer what has changed and why a
              line is the way it is. They read the repository directly and need no approval, so use
              them rather than running git yourself.
            - Prefer the refactoring tools over hand-editing text when one fits: `rename_symbol`,
              `safe_delete`, `add_import`, and `insert_member` go through the IDE's own engine, so
              they update every reference instead of just the line in front of you.
            - Reach for `edit_file_lines` for changes those tools don't cover. Rewrite a whole file
              only when you are genuinely replacing it.
            - When `get_file_problems` reports something, try `apply_quick_fix` on that line before
              editing by hand. An unresolved reference is the clearest case: the IDE knows the fully
              qualified name and will add the right import, where guessing at one costs a turn and
              often gets it wrong. Call it without `fix` to see what is on offer, then with the name.
            - Paths are relative to the project root.
            - Match the surrounding code: its naming, its idiom, its comment density. A change should
              be hard to pick out as yours.

            Running things -- these overlap, so pick in this order:
            - `run_configuration` first, when a saved configuration already covers what you want to
              run. It returns per-test results and a real exit code, so it is the way to run tests
              and read back which ones failed.
            - `run_at_location` when none does -- a test class you just wrote will not have one.
              Give it the file and the line of the test class or method and it creates the
              configuration the way the editor's gutter Run button does, then reports the same
              results. Do not ask the user to set a configuration up; this is what it is for.
            - `run_shell_command` for builds, git commands that change something, and anything
              neither of those can launch. It needs the user's approval and its output comes back as
              terminal text.
            - to stop at a breakpoint, `toggle_breakpoint` first, then start the thing under the
              debugger -- `start_debug_configuration` when a saved configuration covers it,
              `run_at_location` with `debug` when none does -- and then `await_breakpoint`. Never
              tell the user a test cannot be debugged because it has no configuration; produce one
              at the location the way you would to run it. Once stopped, `evaluate_expression` asks
              the debugger questions the variable list does not answer -- what an expression comes
              to, what a call returns right here. Reach for it instead of adding a print statement
              and running again. It executes what you write, so read state rather than change it.
            - `run_action` for IDE commands that are not runnable any other way. It reports whether
              the action ran, not what it produced, so do not use it to run tests.

            Your file changes are tracked, shown to the user as diffs, and can be reverted as a
            group, so you do not need to explain every edit in prose -- but do say what you changed
            and why in a sentence or two. Reference files as `path/to/File.kt:42`.

            Be concise. The user is reading your replies in a narrow tool window next to their code:
            lead with the outcome, keep supporting detail short, and skip preamble. Report results
            faithfully -- if a command failed, say so and show the output; if you could not finish
            something, say what is left rather than implying it is done.
        """.trimIndent()

        /**
         * How far doubling the output cap will go on its own. The models themselves allow far more,
         * but [AICodingAgentClient] waits 60 seconds for a response and does not stream: past roughly
         * this many tokens the reply stops arriving in time, and a timeout is a worse answer than a
         * truncated one. Not a hard limit -- it is where the automatic raises stop and the user is
         * asked for a number instead, which is a judgement about their connection and patience that
         * this constant cannot make.
         */
        const val MAX_TOKENS_CEILING = 12_000

        /** Stands in for a tool that was skipped or interrupted when the user pressed Stop. */
        private const val CANCELLED_RESULT = "The user cancelled this turn before the tool finished."

        /** Stands in for a tool the loop never reached, because the turn failed on its way there. */
        private const val ABANDONED_RESULT = "This tool never ran: the turn ended with an error before it started."

        /**
         * Sent as a user turn to pick up a cut-off answer. It has to be a *user* message: the
         * current models reject a request whose final message is an assistant one (prefilling),
         * so the truncated turn cannot simply be left at the end of the history to be continued.
         */
        private const val CONTINUE_PROMPT =
            "Your previous response was cut off because it hit the output token limit. " +
                "Continue from exactly where you stopped -- do not repeat what you already wrote " +
                "and do not start over."

        /**
         * The same job as [CONTINUE_PROMPT] for the turn that ran out mid-tool-call. It needs its
         * own wording because [withoutTrailingToolUse] has already dropped that block: telling the
         * model to continue where it stopped would point it at something no longer in the history,
         * and often at nothing at all, since a turn that went straight to a tool call leaves no text
         * behind. What it has to be told is that the call never ran.
         */
        private const val RETRY_TOOL_CALL_PROMPT =
            "Your previous response was cut off because it hit the output token limit, part-way " +
                "through a tool call. The call was discarded and never ran. Make it again, picking " +
                "up from any text you had already written -- do not repeat that text."
    }

    /**
     * Resolved once, off the EDT, on the first request -- and stable for the rest of the
     * conversation, which keeps it inside the cacheable prefix of every subsequent turn.
     */
    private val basePrompt: String by lazy {
        val facts = runCatching { environment() }.getOrDefault("").trim()
        if (facts.isEmpty()) SYSTEM_PROMPT else "$SYSTEM_PROMPT\n\n$facts"
    }

    /** [basePrompt] with this turn's skill listing on the end. */
    private fun systemPrompt(): String {
        val listing = runCatching { skills() }.getOrDefault("").trim()
        return if (listing.isEmpty()) basePrompt else "$basePrompt\n\n$listing"
    }

    private val log = Logger.getInstance(AICodingAgent::class.java)

    /**
     * Runs the loop, mutating [history] in place with every assistant/tool-result turn produced.
     *
     * [isCancelled] is polled at every point where the loop is about to spend something -- a request
     * or a tool call. Cancelling never leaves [history] malformed: a turn that asked for tools is
     * always answered with a result for each of them, so the conversation can be continued.
     *
     * [maxIterations] is how many request/tool-call rounds the turn gets before the loop stops and
     * asks the user whether to go on. Not a hard ceiling: every yes to [Listener.onMaxIterations]
     * buys that many more.
     *
     * [maxTokens] is where the reply-length cap starts, not where it stays: continuing a cut-off
     * reply doubles it up to [MAX_TOKENS_CEILING], and past that [Listener.onMaxTokens] chooses the
     * cap outright. The raise lasts as long as the turn does -- carrying it into the next one is the
     * caller's to do, from the value it returned there.
     */
    fun run(
        endpoint: AICodingAgentEndpoint,
        model: String,
        maxTokens: Int,
        maxIterations: Int,
        history: MutableList<ChatMessage>,
        listener: Listener,
        isCancelled: () -> Boolean = { false },
    ) {
        // Once for the whole turn, not once per iteration: connecting to an MCP server is not free,
        // and a tool list that changed underneath the loop would leave the model calling a tool
        // that no longer exists.
        val available = tools()
        val toolsByName = available.associateBy { it.name }
        val toolDefinitions = available.map { it.toDefinition() }
        // Same reasoning as the tool list: resolved once for the whole turn, so the system prompt
        // cannot change underneath the loop between one iteration and the next.
        val system = systemPrompt()

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

                if (isCancelled()) return
                val turn =
                    AICodingAgentClient.sendMessage(endpoint, model, tokenCap, history, toolDefinitions, system)
                turn.usage?.let(listener::onUsage)
                val truncated = turn.stopReason == "max_tokens"
                val content = if (truncated) withoutTrailingToolUse(turn.content) else turn.content
                // Whether the limit landed mid-tool-call rather than mid-sentence, which is the
                // difference between a reply to be continued and a call to be made again.
                val droppedToolUse = content.size() < turn.content.size()
                if (content.size() > 0) {
                    history.add(ChatMessage("assistant", content))
                }

                for (block in content) {
                    val obj = block.asJsonObject
                    if (obj.get("type")?.asString == "text") {
                        listener.onAssistantText(obj.get("text")?.asString.orEmpty())
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
                for (block in toolUseBlocks) {
                    val obj = block.asJsonObject
                    val toolName = obj.get("name")?.asString.orEmpty()
                    val toolUseId = obj.get("id")?.asString.orEmpty()
                    val input = obj.getAsJsonObject("input") ?: JsonObject()

                    // Cancelling stops the work but still answers the block: an assistant turn whose
                    // tool_use has no matching tool_result is rejected by the API on the next message.
                    var outcome = ToolOutcome.OK
                    val resultText = if (isCancelled()) {
                        outcome = ToolOutcome.CANCELLED
                        CANCELLED_RESULT
                    } else {
                        listener.onToolStarted(toolName, input, toolsByName[toolName]?.interruptible ?: true)
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

                    listener.onToolCall(toolName, input, resultText, outcome)

                    toolResults.add(JsonObject().apply {
                        addProperty("type", "tool_result")
                        addProperty("tool_use_id", toolUseId)
                        addProperty("content", resultText)
                    })
                }

                history.add(ChatMessage("user", toolResults))
            }
        } finally {
            answerDanglingToolUse(history)
        }
    }

    /**
     * Answers any `tool_use` blocks the loop did not get to, so the conversation stays sendable.
     *
     * The API rejects a request whose history holds a `tool_use` with no matching `tool_result`, and
     * it rejects it every time -- the offending turn is still there on the next message, and the one
     * after that. So a turn that fell over between asking for a tool and answering it does not just
     * fail once, it leaves the chat dead for good: every later message comes back with the same
     * error, and only starting a new conversation, or restarting the IDE onto a chat saved before
     * the break, gets out of it. Writing the missing results closes that hole wherever the loop left
     * off -- an exception, an Error, a cancelled thread.
     */
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

    /**
     * Drops a `tool_use` block left dangling by the token limit. Its arguments were cut off
     * mid-JSON, so it cannot be executed, and sending it back without a matching `tool_result`
     * would have the API reject the next request outright.
     */
    private fun withoutTrailingToolUse(content: JsonArray): JsonArray {
        val last = content.lastOrNull()?.asJsonObject ?: return content
        if (last.get("type")?.asString != "tool_use") return content

        val trimmed = JsonArray()
        for (i in 0 until content.size() - 1) trimmed.add(content[i])
        return trimmed
    }
}
