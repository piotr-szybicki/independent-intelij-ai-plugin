package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool

/**
 * Evaluate Expression, as a tool -- the thing you actually reach for at a breakpoint.
 *
 * `await_breakpoint` and `debugger_action` report the variables in scope, which answers "what is
 * `user`" but not "what does `user.orders.filter { it.isOpen }.size` come to", and not "is
 * `repository.findById(id)` returning null here". Those are the questions a breakpoint exists to
 * answer, and until now the only way to ask them was to edit in a print statement and run again.
 *
 * The evaluation itself lives in [DebuggerPause] alongside the variable reading, because rendering
 * the answer is the same asynchronous dance either way.
 */
class EvaluateExpressionTool(private val project: Project) : AICodingAgentTool {

    companion object {
        private const val DEFAULT_TIMEOUT_SECONDS = 20
        private const val MAX_TIMEOUT_SECONDS = 120
    }

    private val pause = DebuggerPause(project)

    override val name = "evaluate_expression"
    override val description =
        "Evaluates an expression in the frame the debugger is stopped in and returns its value. " +
            "The session must already be paused; pair with await_breakpoint. Write it in the " +
            "debuggee's language. It runs for real, so prefer reading state over calling methods " +
            "with side effects."
    override val inputSchema: JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("expression", JsonObject().apply {
                addProperty("type", "string")
                addProperty(
                    "description",
                    "The expression to evaluate, in the debuggee's language, e.g. \"user.orders.size\"",
                )
            })
            add("timeout_seconds", JsonObject().apply {
                addProperty("type", "integer")
                addProperty(
                    "description",
                    "How long to wait for the debugger to answer. Defaults to $DEFAULT_TIMEOUT_SECONDS, " +
                        "maximum $MAX_TIMEOUT_SECONDS.",
                )
            })
        })
        add("required", JsonArray().apply { add("expression") })
    }

    override fun execute(input: JsonObject): String {
        val expression = input.get("expression")?.asString?.trim().orEmpty()
        if (expression.isEmpty()) return "Error: missing 'expression'"

        val timeoutSeconds = (input.get("timeout_seconds")?.asInt ?: DEFAULT_TIMEOUT_SECONDS)
            .coerceIn(1, MAX_TIMEOUT_SECONDS)

        val session = pausedSession() ?: return notPausedMessage()

        return pause.evaluate(session, expression, timeoutSeconds * 1000L)
    }

    /**
     * The session to evaluate against, preferring the one the UI considers current -- the user may
     * have several, and that is the one whose frame they are looking at.
     */
    private fun pausedSession(): XDebugSession? {
        val manager = XDebuggerManager.getInstance(project)
        manager.currentSession?.takeIf { it.isPaused && !it.isStopped }?.let { return it }
        return manager.debugSessions.firstOrNull { it.isPaused && !it.isStopped }
    }

    private fun notPausedMessage(): String {
        val live = XDebuggerManager.getInstance(project).debugSessions.filterNot { it.isStopped }
        return if (live.isEmpty()) {
            "No debug session is running, so there is no frame to evaluate in. Set a breakpoint with " +
                "toggle_breakpoint and start one with start_debug_configuration -- or with " +
                "run_at_location and debug=true when nothing suitable is saved -- then wait for it " +
                "with await_breakpoint."
        } else {
            "The debugger is running (${live.joinToString { it.sessionName }}) but is not paused, so " +
                "there is no frame to evaluate in. Use await_breakpoint to wait for it to stop first."
        }
    }
}
