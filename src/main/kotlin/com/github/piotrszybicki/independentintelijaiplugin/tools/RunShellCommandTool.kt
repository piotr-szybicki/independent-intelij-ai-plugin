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

class RunShellCommandTool(private val project: Project) : AICodingAgentTool {

    companion object {
        private const val DEFAULT_TIMEOUT_SECONDS = 120
        private const val MAX_TIMEOUT_SECONDS = 600
        private const val POLL_INTERVAL_MILLIS = 150L

        private const val MAX_OUTPUT_CHARS = 20_000

        private const val TAB_NAME = "AI"

        fun shellDialect(project: Project): String = when (Shell.of(project)) {
            Shell.POWERSHELL -> "PowerShell"
            Shell.CMD -> "cmd.exe"
            Shell.POSIX -> "a POSIX shell (bash/zsh)"
        }
    }

    override val interruptible = false

    private val approveAll = AtomicBoolean(false)

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

        val before = textOf(widget)
        ApplicationManager.getApplication().invokeAndWait { widget.sendCommandToExecute(wrapped) }

        val done = Regex(Regex.escape(marker) + "(\\d+)")
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
        var match: MatchResult? = null
        var delta = ""

        while (System.currentTimeMillis() < deadline) {
            delta = delta(before, textOf(widget))
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


    private fun widgetFor(workDir: File): TerminalWidget {
        openTab?.let { if (it.alive && it.workDir == workDir) return it.widget }

        var created: TerminalWidget? = null
        var failure: Exception? = null
        ApplicationManager.getApplication().invokeAndWait {
            try {
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
        widget.addTerminationCallback({ tab.alive = false }, widget)
        openTab = tab
        return widget
    }

    private fun textOf(widget: TerminalWidget): String =
        runCatching { widget.getText().toString() }.getOrDefault("")

    private fun delta(before: String, now: String): String =
        if (before.isNotEmpty() && now.startsWith(before)) now.substring(before.length) else now

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


    private enum class Shell {
        POSIX, POWERSHELL, CMD;

        fun markerSuffix(marker: String): String = when (this) {
            POSIX -> "; echo \"$marker\$?\""
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
