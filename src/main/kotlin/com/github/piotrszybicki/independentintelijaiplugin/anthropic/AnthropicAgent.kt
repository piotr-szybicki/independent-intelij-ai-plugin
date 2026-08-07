package com.github.piotrszybicki.independentintelijaiplugin.anthropic

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger

/**
 * Drives the request/tool-call/tool-result cycle described at
 * https://docs.anthropic.com/en/docs/build-with-claude/tool-use until the model stops asking for tools.
 */
class AnthropicAgent(
    tools: List<AnthropicTool>,
    /**
     * Machine-specific facts appended to the system prompt. A lambda rather than a string because
     * describing the environment reads IDE settings, which must not happen on the EDT -- the agent
     * is constructed there, but [systemPrompt] is not resolved until the first request.
     */
    private val environment: () -> String = { "" },
    private val maxIterations: Int = 10,
) {

    interface Listener {
        fun onAssistantText(text: String)
        fun onToolCall(name: String, input: JsonObject, result: String)

        /**
         * A tool is about to run. [interruptible] is false when stopping the turn would only stop
         * the waiting, leaving the work itself running.
         */
        fun onToolStarted(name: String, interruptible: Boolean) {}

        /**
         * Called when a turn was cut off by the model's output limit. Returning true resumes the
         * loop with a request to continue; returning false ends it, leaving the partial answer.
         */
        fun onMaxTokens(): Boolean
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
            - Read before you edit. `read_project_file` and `find_in_files` are cheap; guessing at a
              file's contents is not. `read_project_file` takes a line range -- use it once you know
              which lines you want rather than pulling in whole files.
            - `get_file_structure` is how you find out which lines those are. It outlines a file's
              declarations with their line numbers for a fraction of the cost of reading it, so on
              anything but a short file, outline first and then read the range you need.
            - `get_symbol_info` answers "what is this?" in one call. Point it at a call, a type, or
              any name you are unsure of and it returns the declaration -- signature, doc comment and
              source -- including for library and JDK symbols no project file contains. Reach for it
              instead of reading a whole file to find one signature, and before `find_usages` or
              `rename_symbol` when several declarations share a name.
            - `find_implementations` for "who implements this" and "what overrides this".
              `find_usages` will not answer either: a class can implement an interface without ever
              mentioning its methods by name, so it shows up in one and not the other.
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
            - `run_shell_command` for builds, git, and anything neither of those can launch. It
              needs the user's approval and its output comes back as terminal text.
            - `start_debug_configuration` only when you need to stop at a breakpoint; pair it with
              `toggle_breakpoint` and `await_breakpoint`.
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
         * Sent as a user turn to pick up a cut-off answer. It has to be a *user* message: the
         * current models reject a request whose final message is an assistant one (prefilling),
         * so the truncated turn cannot simply be left at the end of the history to be continued.
         */
        /** Stands in for a tool that was skipped or interrupted when the user pressed Stop. */
        private const val CANCELLED_RESULT = "The user cancelled this turn before the tool finished."

        private const val CONTINUE_PROMPT =
            "Your previous response was cut off because it hit the output token limit. " +
                "Continue from exactly where you stopped -- do not repeat what you already wrote " +
                "and do not start over."
    }

    /**
     * Resolved once, off the EDT, on the first request -- and stable for the rest of the
     * conversation, which keeps it inside the cacheable prefix of every subsequent turn.
     */
    private val systemPrompt: String by lazy {
        val facts = runCatching { environment() }.getOrDefault("").trim()
        if (facts.isEmpty()) SYSTEM_PROMPT else "$SYSTEM_PROMPT\n\n$facts"
    }

    private val log = Logger.getInstance(AnthropicAgent::class.java)
    private val toolsByName = tools.associateBy { it.name }
    private val toolDefinitions = tools.map { it.toDefinition() }

    /**
     * Runs the loop, mutating [history] in place with every assistant/tool-result turn produced.
     *
     * [isCancelled] is polled at every point where the loop is about to spend something -- a request
     * or a tool call. Cancelling never leaves [history] malformed: a turn that asked for tools is
     * always answered with a result for each of them, so the conversation can be continued.
     */
    fun run(
        endpoint: AnthropicEndpoint,
        model: String,
        maxTokens: Int,
        history: MutableList<ChatMessage>,
        listener: Listener,
        isCancelled: () -> Boolean = { false },
    ) {
        repeat(maxIterations) {
            if (isCancelled()) return
            val turn =
                AnthropicClient.sendMessage(endpoint, model, maxTokens, history, toolDefinitions, systemPrompt)
            val truncated = turn.stopReason == "max_tokens"
            val content = if (truncated) withoutTrailingToolUse(turn.content) else turn.content
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
                // another round trip, so ask before spending one.
                if (!listener.onMaxTokens()) return
                history.add(ChatMessage.text("user", CONTINUE_PROMPT))
                return@repeat
            }

            val toolResults = JsonArray()
            for (block in toolUseBlocks) {
                val obj = block.asJsonObject
                val toolName = obj.get("name")?.asString.orEmpty()
                val toolUseId = obj.get("id")?.asString.orEmpty()
                val input = obj.getAsJsonObject("input") ?: JsonObject()

                // Cancelling stops the work but still answers the block: an assistant turn whose
                // tool_use has no matching tool_result is rejected by the API on the next message.
                val resultText = if (isCancelled()) {
                    CANCELLED_RESULT
                } else {
                    listener.onToolStarted(toolName, toolsByName[toolName]?.interruptible ?: true)
                    runCatching {
                        val tool = toolsByName[toolName] ?: throw AnthropicApiException("Unknown tool: $toolName")
                        tool.execute(input)
                    }.getOrElse { e ->
                        log.info("Tool '$toolName' failed: ${e.message}")
                        if (isCancelled()) CANCELLED_RESULT else "Error: ${e.message}"
                    }
                }

                listener.onToolCall(toolName, input, resultText)

                toolResults.add(JsonObject().apply {
                    addProperty("type", "tool_result")
                    addProperty("tool_use_id", toolUseId)
                    addProperty("content", resultText)
                })
            }

            history.add(ChatMessage("user", toolResults))
        }

        log.info("AnthropicAgent stopped after reaching the $maxIterations tool-call iteration cap")
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
