package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.terminal.ui.TerminalWidget
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool
import org.jetbrains.plugins.terminal.TerminalProjectOptionsProvider
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs a shell command in the IDE's Terminal tool window and returns what it printed.
 *
 * Going through the terminal rather than spawning the process headlessly is what makes a long or
 * wedged command survivable: it is a real interactive session, so the user watches it scroll and
 * stops it with Ctrl+C. The cost is that the output has to be read back off the terminal's own
 * text, which is why the command is wrapped in an end marker -- there is no exit code to read
 * otherwise, and no other way to tell "still running" from "finished quietly".
 *
 * Unlike every other tool here, this one cannot be bounded by the project: the path checks that
 * keep `read_project_file` and `delete_file` inside the project root mean nothing to a shell. So
 * the gate is the user, not a validator -- each command is shown for approval before it is typed,
 * with an opt-out that lasts until the chat is reset.
 *
 * Files a command writes are also outside the change session: it edits on disk rather than through
 * documents, so Approve/Revert has no baseline for them.
 */
class RunShellCommandTool(private val project: Project) : AICodingAgentTool {

    companion object {
        private const val DEFAULT_TIMEOUT_SECONDS = 120
        private const val MAX_TIMEOUT_SECONDS = 600
        private const val POLL_INTERVAL_MILLIS = 150L

        /** Command output is frequently enormous and the tail is the part that matters. */
        private const val MAX_OUTPUT_CHARS = 20_000

        private const val TAB_NAME = "AI"

        /**
         * Names the terminal's shell for the system prompt, so commands are written in the dialect
         * that will actually interpret them rather than whatever the model assumes.
         *
         * Reads a settings value, so this must not run on the EDT.
         */
        fun shellDialect(project: Project): String = when (Shell.of(project)) {
            Shell.POWERSHELL -> "PowerShell"
            Shell.CMD -> "cmd.exe"
            Shell.POSIX -> "a POSIX shell (bash/zsh)"
        }
    }

    /**
     * The command belongs to the terminal, not to this thread: interrupting the wait would leave it
     * running with nobody reading its output. Ctrl+C in the terminal is the way to stop it.
     */
    override val interruptible = false

    /** Survives individual calls, not the chat: [forgetApprovals] resets it on a new conversation. */
    private val approveAll = AtomicBoolean(false)

    /** The tab from the previous call, reused while it is alive and rooted at the same directory. */
    private var openTab: Tab? = null

    private class Tab(val workDir: File, val widget: TerminalWidget) {
        @Volatile
        var alive = true
    }

    override val name = "run_shell_command"
    override val description =
        "Runs a shell command in the IDE's Terminal and returns its exit code and output. For " +
            "builds, git, and anything with no run configuration; prefer run_configuration when " +
            "one exists, as it reports per-test results. The user approves each command and " +
            "watches it run, so send one purposeful command rather than a chain of exploratory " +
            "ones. Use the file tools to read and edit files. Interactive commands hang until the " +
            "user types into the terminal or interrupts."
    override val inputSchema: JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("command", JsonObject().apply {
                addProperty("type", "string")
                addProperty(
                    "description",
                    "The command line to run, in the syntax of the shell configured for the IDE " +
                        "terminal. Pipes and redirection are allowed.",
                )
            })
            add("working_dir", JsonObject().apply {
                addProperty("type", "string")
                addProperty(
                    "description",
                    "Directory to run in, relative to the project root. Defaults to the project root.",
                )
            })
            add("timeout_seconds", JsonObject().apply {
                addProperty("type", "integer")
                addProperty(
                    "description",
                    "How long to wait for the command to finish before giving up on its output. " +
                        "The command keeps running in the terminal either way. Defaults to " +
                        "$DEFAULT_TIMEOUT_SECONDS, maximum $MAX_TIMEOUT_SECONDS.",
                )
            })
        })
        add("required", JsonArray().apply { add("command") })
    }

    /** Called when a new chat starts, so a previous conversation's blanket approval does not carry over. */
    fun forgetApprovals() {
        approveAll.set(false)
    }

    override fun execute(input: JsonObject): String {
        val command = input.get("command")?.asString?.trim().orEmpty()
        if (command.isEmpty()) return "Error: missing 'command'"

        val relativeDir = input.get("working_dir")?.asString
        val workDir = if (relativeDir.isNullOrBlank()) {
            project.basePath?.let(::File) ?: return "Error: the project has no directory on disk"
        } else {
            PsiTargets.resolveProjectPath(project, relativeDir)
                ?: return "Error: working_dir is outside the project directory: $relativeDir"
        }
        if (!workDir.isDirectory) return "Error: working_dir is not a directory: ${workDir.path}"

        val timeoutSeconds = (input.get("timeout_seconds")?.asInt ?: DEFAULT_TIMEOUT_SECONDS)
            .coerceIn(1, MAX_TIMEOUT_SECONDS)

        if (!confirm(command, workDir, timeoutSeconds)) {
            // Reported as a result rather than an error: the user declining is a normal outcome,
            // and the model should adapt instead of retrying the same command.
            return "The user declined to run this command. Do not run it again; ask what to do instead."
        }

        val shell = Shell.of(project)
        val marker = "CLAUDE_DONE_${System.nanoTime().toString(36)}_"
        val wrapped = command + shell.markerSuffix(marker)

        val widget = try {
            widgetFor(workDir)
        } catch (e: Exception) {
            return "Error: could not open a terminal: ${e.message}"
        }

        // Snapshot first: the completed output is whatever the terminal grew by, which is far more
        // reliable than trying to find the echoed command line inside a wrapped, scrolling buffer.
        val before = textOf(widget)
        ApplicationManager.getApplication().invokeAndWait { widget.sendCommandToExecute(wrapped) }

        val done = Regex(Regex.escape(marker) + "(\\d+)")
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
        var match: MatchResult? = null
        var delta = ""

        while (System.currentTimeMillis() < deadline) {
            delta = delta(before, textOf(widget))
            // Only the echoed line carries the marker without a number after it -- the shell has
            // not expanded `$?` yet at that point -- so requiring digits skips the false match.
            match = done.find(delta)
            if (match != null) break
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return "Error: interrupted while waiting for the command to finish"
            }
        }

        val body = trimToOutput(delta, marker, match)
        return buildString {
            append("$ $command\n")
            append("(in ${workDir.path}, IDE terminal tab \"$TAB_NAME\")\n")
            if (match != null) {
                append("Exit code: ${match.groupValues[1]}\n")
            } else {
                append(
                    "Did not finish within ${timeoutSeconds}s -- no exit code. It is either still " +
                        "running in the terminal or the user stopped it with Ctrl+C. Output so far:\n",
                )
            }
            if (body.isBlank()) append("\n(no output)") else append("\n$body")
        }
    }

    // --- terminal -------------------------------------------------------------------------------

    private fun widgetFor(workDir: File): TerminalWidget {
        openTab?.let { if (it.alive && it.workDir == workDir) return it.widget }

        var created: TerminalWidget? = null
        var failure: Exception? = null
        ApplicationManager.getApplication().invokeAndWait {
            try {
                // Deprecated in 2026.2 with no documented replacement that preserves these
                // arguments; createNewSession(dir, name, command, ...) takes a shell command line
                // instead, so switching needs the terminal API pinned down first.
                @Suppress("DEPRECATION")
                val shellWidget = TerminalToolWindowManager.getInstance(project)
                    .createShellWidget(workDir.path, TAB_NAME, false, false)
                created = shellWidget
            } catch (e: Exception) {
                failure = e
            }
        }
        failure?.let { throw it }
        val widget = created ?: throw IllegalStateException("the terminal returned no widget")

        val tab = Tab(workDir, widget)
        // A closed tab leaves a widget that reads as permanently empty, which would look like a
        // command that produced nothing rather than a tab that is gone.
        widget.addTerminationCallback({ tab.alive = false }, widget)
        openTab = tab
        return widget
    }

    private fun textOf(widget: TerminalWidget): String =
        runCatching { widget.getText().toString() }.getOrDefault("")

    /** The text the terminal gained since [before], falling back to everything if it has scrolled. */
    private fun delta(before: String, now: String): String =
        if (before.isNotEmpty() && now.startsWith(before)) now.substring(before.length) else now

    /**
     * Strips the terminal's own echo of the command off the front and the end marker off the back,
     * leaving what the command actually printed.
     */
    private fun trimToOutput(delta: String, marker: String, match: MatchResult?): String {
        val echoed = delta.indexOf(marker)
        val start = if (echoed < 0) 0 else (delta.indexOf('\n', echoed) + 1).coerceAtLeast(0)
        val end = match?.range?.first ?: delta.length
        if (start >= end) return ""

        val body = delta.substring(start, end).trim('\n', '\r', ' ')
        if (body.length <= MAX_OUTPUT_CHARS) return body

        return "[TRUNCATED: first ${body.length - MAX_OUTPUT_CHARS} characters omitted]\n" +
            body.takeLast(MAX_OUTPUT_CHARS)
    }

    // --- approval -------------------------------------------------------------------------------

    /** Blocks the agent thread on the EDT dialog: nothing may run until the user has decided. */
    private fun confirm(command: String, workDir: File, timeoutSeconds: Int): Boolean {
        if (approveAll.get()) return true

        var choice = -1
        ApplicationManager.getApplication().invokeAndWait {
            choice = Messages.showDialog(
                project,
                "The AI wants to run a shell command in the Terminal tool window:\n\n$command\n\n" +
                    "Directory: ${workDir.path}\nWaits up to ${timeoutSeconds}s for it to finish\n\n" +
                    "It runs with your account's permissions and is not limited to the project. " +
                    "You can stop it with Ctrl+C in the terminal.",
                "Run Shell Command?",
                arrayOf("Run", "Always Run in This Chat", "Don't Run"),
                2,
                Messages.getWarningIcon(),
            )
        }

        if (choice == 1) approveAll.set(true)
        return choice == 0 || choice == 1
    }

    // --- shell dialects -------------------------------------------------------------------------

    /**
     * The end marker has to be appended in the syntax of whichever shell the terminal is configured
     * to start, since that is the process doing the interpreting -- not the one this plugin picks.
     */
    private enum class Shell {
        POSIX, POWERSHELL, CMD;

        fun markerSuffix(marker: String): String = when (this) {
            POSIX -> "; echo \"$marker\$?\""
            // $LASTEXITCODE is only set by native commands, so a cmdlet would otherwise print a
            // marker with nothing after it and never match.
            POWERSHELL -> "; \$c=\$LASTEXITCODE; if(\$null -eq \$c){\$c=if(\$?){0}else{1}}; echo \"$marker\$c\""
            CMD -> "& echo $marker%ERRORLEVEL%"
        }

        companion object {
            fun of(project: Project): Shell {
                val path = runCatching {
                    TerminalProjectOptionsProvider.getInstance(project).shellPath
                }.getOrNull().orEmpty().lowercase()
                return when {
                    path.contains("powershell") || path.contains("pwsh") -> POWERSHELL
                    path.contains("cmd.exe") -> CMD
                    else -> POSIX
                }
            }
        }
    }
}
