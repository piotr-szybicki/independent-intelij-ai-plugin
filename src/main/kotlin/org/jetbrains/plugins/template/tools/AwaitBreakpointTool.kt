package org.jetbrains.plugins.template.tools

import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebuggerManager
import org.jetbrains.plugins.template.anthropic.AnthropicTool

/**
 * Blocks until the debugger stops -- at a breakpoint, or on a step -- and reports where it stopped
 * along with the variables in scope there. The waiting and reading themselves live in
 * [DebuggerPause], shared with [DebuggerActionTool].
 */
class AwaitBreakpointTool(private val project: Project) : AnthropicTool {

    companion object {
        private const val DEFAULT_TIMEOUT_SECONDS = 60
        private const val MAX_TIMEOUT_SECONDS = 600
    }

    private val pause = DebuggerPause(project)

    override val name = "await_breakpoint"
    override val description =
        "Waits until the running debugger stops at a breakpoint (or finishes a step) and returns " +
            "the file and line it stopped on plus the variables in scope, with their values. " +
            "Returns straight away if it is already stopped. Requires a debug session the user has " +
            "started or that start_debug_configuration attached; use debugger_action to move on " +
            "from the pause afterwards."
    override val inputSchema: JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("timeout_seconds", JsonObject().apply {
                addProperty("type", "integer")
                addProperty(
                    "description",
                    "How long to wait for the debugger to stop. Defaults to $DEFAULT_TIMEOUT_SECONDS, " +
                        "maximum $MAX_TIMEOUT_SECONDS.",
                )
            })
        })
    }

    override fun execute(input: JsonObject): String {
        val timeoutSeconds = (input.get("timeout_seconds")?.asInt ?: DEFAULT_TIMEOUT_SECONDS)
            .coerceIn(1, MAX_TIMEOUT_SECONDS)

        val session = pause.awaitPause(timeoutSeconds * 1000L, acceptAlreadyPaused = true)
            ?: return timeoutMessage(timeoutSeconds)

        return pause.describe(session) + "\n\nExecution is still paused; use debugger_action to continue."
    }

    private fun timeoutMessage(timeoutSeconds: Int): String {
        val sessions = XDebuggerManager.getInstance(project).debugSessions.filterNot { it.isStopped }
        return if (sessions.isEmpty()) {
            "No debug session is running, so nothing can hit a breakpoint. Ask the user to start " +
                "one, or use start_debug_configuration if they have a run configuration for it."
        } else {
            "The debugger did not stop within ${timeoutSeconds}s. It is running " +
                "(${sessions.joinToString { it.sessionName }}) but has not reached a breakpoint -- " +
                "the code may not have run, or the breakpoint may be on a line it does not reach."
        }
    }
}
