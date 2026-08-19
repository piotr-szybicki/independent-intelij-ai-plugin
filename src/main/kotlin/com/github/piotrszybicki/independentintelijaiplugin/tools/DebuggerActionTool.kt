package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool

class DebuggerActionTool(private val project: Project) : AICodingAgentTool {

    companion object {
        private const val DEFAULT_WAIT_SECONDS = 15
        private const val MAX_WAIT_SECONDS = 300
    }

    private val pause = DebuggerPause(project)

    private enum class Action(val id: String, val needsPause: Boolean, val expectsStop: Boolean) {
        RESUME("resume", needsPause = true, expectsStop = true),
        STEP_OVER("step_over", needsPause = true, expectsStop = true),
        STEP_INTO("step_into", needsPause = true, expectsStop = true),
        FORCE_STEP_INTO("force_step_into", needsPause = true, expectsStop = true),
        STEP_OUT("step_out", needsPause = true, expectsStop = true),
        RUN_TO_LINE("run_to_line", needsPause = true, expectsStop = true),

        // Pause acts on a running program; stop ends the session and nothing comes back.
        PAUSE("pause", needsPause = false, expectsStop = true),
        STOP("stop", needsPause = false, expectsStop = false);

        companion object {
            fun of(id: String) = entries.firstOrNull { it.id == id }
        }
    }

    override val name = "debugger_action"
    override val description =
        "Drives a paused debugger: resume, step_over, step_into, force_step_into, step_out, " +
            "run_to_line (also takes path and line), pause, stop. Returns where execution stopped " +
            "next and the variables in scope there."
    override val inputSchema: JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("action", JsonObject().apply {
                addProperty("type", "string")
                add("enum", JsonArray().apply { Action.entries.forEach { add(it.id) } })
                addProperty(
                    "description",
                    "resume: run until the next breakpoint. step_over: run this line, staying in " +
                        "this method. step_into: enter the call on this line. force_step_into: " +
                        "enter it even if the debugger would normally skip it (library or " +
                        "synthetic code). step_out: run until this method returns. run_to_line: " +
                        "run until a given line, without setting a breakpoint. pause: suspend a " +
                        "running program wherever it is. stop: end the debug session.",
                )
            })
            add("path", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "run_to_line only: file path relative to the project root")
            })
            add("line", JsonObject().apply {
                addProperty("type", "integer")
                addProperty("description", "run_to_line only: 1-based line number to run to")
            })
            add("wait_seconds", JsonObject().apply {
                addProperty("type", "integer")
                addProperty(
                    "description",
                    "How long to wait for execution to stop again before handing back. Defaults to " +
                        "$DEFAULT_WAIT_SECONDS, maximum $MAX_WAIT_SECONDS. Raise it for a resume " +
                        "that has to run a while, or call await_breakpoint afterwards instead.",
                )
            })
        })
        add("required", JsonArray().apply { add("action") })
    }

    override fun execute(input: JsonObject): String {
        val actionId = input.get("action")?.asString ?: return "Error: missing 'action'"
        val action = Action.of(actionId)
            ?: return "Error: unknown action \"$actionId\". Use one of: ${Action.entries.joinToString { it.id }}"

        val waitSeconds = (input.get("wait_seconds")?.asInt ?: DEFAULT_WAIT_SECONDS)
            .coerceIn(1, MAX_WAIT_SECONDS)

        val session = XDebuggerManager.getInstance(project).currentSession?.takeUnless { it.isStopped }
            ?: return "Error: no debug session is running. Start one with start_debug_configuration, " +
                "or with run_at_location and debug=true when nothing suitable is saved."

        if (action.needsPause && !session.isPaused) {
            return "Error: the debugger is running, not stopped, so \"$actionId\" does not apply. " +
                "Wait for it with await_breakpoint, or use \"pause\" to suspend it where it is."
        }
        if (action == Action.PAUSE && session.isPaused) {
            return "The debugger is already paused.\n\n" + pause.describe(session)
        }

        // Resolved before the action runs so a bad path fails without disturbing the session.
        val runTo = if (action == Action.RUN_TO_LINE) {
            val path = input.get("path")?.asString ?: return "Error: run_to_line needs 'path'"
            val line = input.get("line")?.asInt ?: return "Error: run_to_line needs 'line'"
            if (line < 1) return "Error: line must be 1 or greater"
            val file = PsiTargets.resolveProjectFile(project, path)
                ?: return "Error: file not found in the project: $path"
            val position = XDebuggerUtil.getInstance().createPosition(file, line - 1)
                ?: return "Error: $path:$line is not a position the debugger can run to"
            RunTarget(path, line, position)
        } else {
            null
        }

        if (!action.expectsStop) {
            var failure: Exception? = null
            ApplicationManager.getApplication().invokeAndWait {
                runCatching { session.stop() }.onFailure { failure = it as? Exception }
            }
            failure?.let { return "Error: could not stop the session: ${it.message}" }
            return "Stopped debug session \"${session.sessionName}\"."
        }

        // The listeners have to be in place before the step is issued: a step can complete before
        // the call that started it returns, and that pause would otherwise be missed entirely.
        val landed = pause.awaitPause(waitSeconds * 1000L, acceptAlreadyPaused = false) {
            ApplicationManager.getApplication().invokeAndWait { perform(session, action, runTo) }
        }

        return report(action, actionId, runTo, session, landed, waitSeconds)
    }

    private class RunTarget(val path: String, val line: Int, val position: com.intellij.xdebugger.XSourcePosition)

    private fun perform(session: XDebugSession, action: Action, runTo: RunTarget?) {
        when (action) {
            Action.RESUME -> session.resume()
            // false: honour breakpoints on the way, which is what the toolbar button does.
            Action.STEP_OVER -> session.stepOver(false)
            Action.STEP_INTO -> session.stepInto()
            Action.FORCE_STEP_INTO -> session.forceStepInto()
            Action.STEP_OUT -> session.stepOut()
            Action.RUN_TO_LINE -> runTo?.let { session.runToPosition(it.position, false) }
            Action.PAUSE -> session.pause()
            Action.STOP -> session.stop()
        }
    }

    private fun report(
        action: Action,
        actionId: String,
        runTo: RunTarget?,
        session: XDebugSession,
        landed: XDebugSession?,
        waitSeconds: Int,
    ): String {
        val did = when (action) {
            Action.RUN_TO_LINE -> "Ran to ${runTo?.path}:${runTo?.line}"
            Action.RESUME -> "Resumed"
            Action.PAUSE -> "Paused"
            else -> "Performed $actionId"
        }

        if (landed != null) return "$did.\n\n" + pause.describe(landed)

        // No pause came back. Which of the two reasons it is matters: one is worth waiting on, the
        // other never will be.
        return if (session.isStopped) {
            "$did, and the debug session ended -- the program ran to completion or was terminated."
        } else {
            "$did, but the program has not stopped again within ${waitSeconds}s. It is still " +
                "running: wait for it with await_breakpoint, or use \"pause\" to suspend it."
        }
    }
}
