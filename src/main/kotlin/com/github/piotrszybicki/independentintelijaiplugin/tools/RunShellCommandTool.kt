package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.execution.RunContentExecutor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.execution.ParametersListUtil
import org.jetbrains.plugins.terminal.TerminalProjectOptionsProvider
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class RunShellCommandTool(
    private val project: Project,
) : AICodingAgentTool {
    companion object {
        private const val MAX_TIMEOUT_SECONDS = 600

        fun shellDialect(project: Project): String = Shell.forProject(project).displayName
    }

    private data class CommandKey(
        val workDir: String,
        val command: String,
    )

    private class ActiveCommand(
        val command: String,
        val workDir: File,
        val pid: Long,
        val consoleTitle: String,
        val output: CommandOutputFile,
        val handler: KillableColoredProcessHandler,
        val finished: CountDownLatch,
    )

    private val approveAll = AtomicBoolean(false)
    private val activeCommands = ConcurrentHashMap<CommandKey, ActiveCommand>()

    override val name = "run_shell_command"
    override val description =
        "Runs one shell process in its own persistent IDE Run console, waits for it to finish, and " +
            "returns its exit code and captured output. The console shows the process-tree root PID " +
            "and keeps the full output after the command ends; the same output is also written to a " +
            "persistent log file. The user can stop the process tree from either the chat or Run " +
            "console. Do not repeat a command merely because it is taking a while. For the unusual " +
            "case where a debugger or background process must outlive this call, set timeout_seconds " +
            "explicitly; an identical command that is still active is reported instead of started twice."
    override val inputSchema: JsonObject =
        JsonObject().apply {
            addProperty("type", "object")
            add(
                "properties",
                JsonObject().apply {
                    add(
                        "command",
                        JsonObject().apply {
                            addProperty("type", "string")
                            addProperty(
                                "description",
                                "The command line to run, in the syntax of the shell configured for the IDE. " +
                                    "Pipes and redirection are allowed.",
                            )
                        },
                    )
                    add(
                        "working_dir",
                        JsonObject().apply {
                            addProperty("type", "string")
                            addProperty(
                                "description",
                                "Directory to run in, relative to the project root. Defaults to the project root.",
                            )
                        },
                    )
                    add(
                        "timeout_seconds",
                        JsonObject().apply {
                            addProperty("type", "integer")
                            addProperty(
                                "description",
                                "Optional. Return after this many seconds while leaving the process and its console " +
                                    "running. Omit for builds, tests, and normal commands so their final output is " +
                                    "returned without rerunning them. Maximum $MAX_TIMEOUT_SECONDS.",
                            )
                        },
                    )
                },
            )
            add("required", JsonArray().apply { add("command") })
        }

    fun forgetApprovals() {
        approveAll.set(false)
    }

    override fun execute(input: JsonObject): String {
        val command =
            input
                .get("command")
                ?.asString
                ?.trim()
                .orEmpty()
        if (command.isEmpty()) return "Error: missing 'command'"

        val relativeDir = input.get("working_dir")?.asString
        val workDir =
            if (relativeDir.isNullOrBlank()) {
                project.basePath?.let(::File) ?: return "Error: the project has no directory on disk"
            } else {
                PsiTargets.resolveProjectPath(project, relativeDir)
                    ?: return "Error: working_dir is outside the project directory: $relativeDir"
            }
        if (!workDir.isDirectory) return "Error: working_dir is not a directory: ${workDir.path}"

        val timeoutSeconds =
            input
                .get("timeout_seconds")
                ?.asInt
                ?.coerceIn(1, MAX_TIMEOUT_SECONDS)

        val normalizedWorkDir = runCatching { workDir.canonicalPath }.getOrDefault(workDir.absolutePath)
        val key = CommandKey(normalizedWorkDir, command)
        activeCommands[key]?.takeIf { !it.handler.isProcessTerminated }?.let { running ->
            return resultFor(running, completed = false, duplicate = true)
        }

        if (!confirm(command, workDir, timeoutSeconds)) {
            return "The user declined to run this command. Do not run it again; ask what to do instead."
        }

        val output =
            try {
                CommandOutputFile.create(project)
            } catch (e: Exception) {
                return "Error: could not create the persistent command log: ${e.message}"
            }

        val commandLine = Shell.forProject(project).commandLine(command, workDir)
        val handler =
            try {
                KillableColoredProcessHandler(commandLine).apply {
                    setShouldDestroyProcessRecursively(true)
                }
            } catch (e: Exception) {
                output.close()
                return "Error: could not start the command: ${e.message}\nLog: ${output.path}"
            }

        val pid = handler.process.pid()
        val consoleTitle = consoleTitle(pid, command)
        val finished = CountDownLatch(1)
        val running = ActiveCommand(command, workDir, pid, consoleTitle, output, handler, finished)

        handler.addProcessListener(
            object : ProcessListener {
                override fun onTextAvailable(
                    event: ProcessEvent,
                    outputType: Key<*>,
                ) {
                    if (!ProcessOutputType.isSystem(outputType)) output.append(event.text)
                }

                override fun processTerminated(event: ProcessEvent) {
                    output.close()
                    activeCommands.remove(key, running)
                    finished.countDown()
                }
            },
        )

        while (true) {
            val alreadyRunning = activeCommands.putIfAbsent(key, running) ?: break
            if (!alreadyRunning.handler.isProcessTerminated) {
                handler.killProcess()
                output.close()
                return resultFor(alreadyRunning, completed = false, duplicate = true)
            }
            activeCommands.remove(key, alreadyRunning)
        }

        try {
            showConsole(running)
        } catch (e: Exception) {
            activeCommands.remove(key, running)
            handler.killProcess()
            output.close()
            return "Error: could not open the command console: ${e.message}\nLog: ${output.path}"
        }

        val completed =
            try {
                if (timeoutSeconds == null) {
                    finished.await()
                    true
                } else {
                    finished.await(timeoutSeconds.toLong(), TimeUnit.SECONDS)
                }
            } catch (e: InterruptedException) {
                stop(handler, finished)
                throw e
            }

        return resultFor(running, completed)
    }

    private fun showConsole(command: ActiveCommand) {
        val banner =
            buildString {
                appendLine("AI shell command")
                appendLine("PID (process-tree root): ${command.pid}")
                appendLine("Directory: ${command.workDir.path}")
                appendLine("Log: ${command.output.path}")
                appendLine("Command: ${command.command}")
                appendLine()
            }

        ApplicationManager.getApplication().invokeAndWait {
            RunContentExecutor(project, command.handler)
                .withTitle(command.consoleTitle)
                .withStop(
                    { command.handler.destroyProcess() },
                    { !command.handler.isProcessTerminated && !command.handler.isProcessTerminating },
                ).run()
            command.handler.notifyTextAvailable(banner, ProcessOutputType.SYSTEM)
        }
    }

    private fun resultFor(
        running: ActiveCommand,
        completed: Boolean,
        duplicate: Boolean = false,
    ): String {
        val body =
            runCatching { running.output.digest(project.basePath) }
                .getOrElse { "[Could not read the persistent log: ${it.message}]" }
        return buildString {
            append("$ ${running.command}\n")
            append("(in ${running.workDir.path}, IDE Run console \"${running.consoleTitle}\")\n")
            append("PID (process-tree root): ${running.pid}\n")
            append("Full log: ${running.output.path}\n")
            when {
                duplicate -> {
                    append("Status: still running; the existing process was returned instead of starting it again.\n")
                }
                completed -> {
                    append("Exit code: ${running.handler.exitCode ?: "unknown"}\n")
                }
                else -> {
                    append("Status: still running after the requested timeout. Do not rerun it.\n")
                }
            }
            if (body.isBlank()) append("\n(no output yet)") else append("\n$body")
        }
    }

    private fun stop(
        handler: KillableColoredProcessHandler,
        finished: CountDownLatch,
    ) {
        if (!handler.isProcessTerminated && !handler.isProcessTerminating) handler.destroyProcess()
        if (!finished.await(5, TimeUnit.SECONDS) && !handler.isProcessTerminated) {
            handler.killProcess()
            finished.await(2, TimeUnit.SECONDS)
        }
    }

    private fun confirm(
        command: String,
        workDir: File,
        timeoutSeconds: Int?,
    ): Boolean {
        if (approveAll.get()) return true

        val waitDescription =
            timeoutSeconds?.let { "Returns after ${it}s if it is still running" }
                ?: "Waits until the command finishes or you stop it"

        var choice = -1
        ApplicationManager.getApplication().invokeAndWait {
            choice =
                Messages.showDialog(
                    project,
                    "The AI wants to run a shell command in its own Run console:\n\n$command\n\n" +
                        "Directory: ${workDir.path}\n$waitDescription\n\n" +
                        "It runs with your account's permissions and is not limited to the project. " +
                        "The console shows its PID and has a Stop button that terminates the process tree.",
                    "Run Shell Command?",
                    arrayOf("Run", "Always Run in This Chat", "Don't Run"),
                    2,
                    Messages.getWarningIcon(),
                )
        }

        if (choice == 1) approveAll.set(true)
        return choice == 0 || choice == 1
    }

    private fun consoleTitle(
        pid: Long,
        command: String,
    ): String {
        val oneLine =
            command
                .lineSequence()
                .firstOrNull()
                .orEmpty()
                .trim()
        val shortened = if (oneLine.length <= 48) oneLine else oneLine.take(45) + "..."
        return "AI [$pid] $shortened"
    }

    private class Shell private constructor(
        private val kind: Kind,
        private val configuredCommand: List<String>,
    ) {
        val displayName: String get() = kind.displayName

        fun commandLine(
            command: String,
            workDir: File,
        ): GeneralCommandLine {
            val executable = configuredCommand.first()
            val configuredArguments = configuredCommand.drop(1).filterNot(kind::isInteractiveOnly)
            val commandArguments =
                when (kind) {
                    Kind.POWERSHELL -> listOf("-Command", command)
                    Kind.CMD -> listOf("/d", "/s", "/c", command)
                    Kind.POSIX -> listOf("-lc", command)
                    Kind.WSL -> listOf("--exec", "sh", "-lc", command)
                }
            return GeneralCommandLine(listOf(executable) + configuredArguments + commandArguments)
                .withWorkDirectory(workDir)
        }

        private enum class Kind(
            val displayName: String,
        ) {
            POSIX("a POSIX shell (bash/zsh)"),
            POWERSHELL("PowerShell"),
            CMD("cmd.exe"),
            WSL("a POSIX shell in WSL"),
            ;

            fun isInteractiveOnly(argument: String): Boolean =
                when (this) {
                    POWERSHELL -> argument.equals("-NoExit", ignoreCase = true)
                    CMD -> argument.equals("/K", ignoreCase = true)
                    POSIX -> argument == "-i"
                    WSL -> false
                }
        }

        companion object {
            fun forProject(project: Project): Shell {
                val raw =
                    runCatching { TerminalProjectOptionsProvider.getInstance(project).shellPath }
                        .getOrNull()
                        .orEmpty()
                val parsed = ParametersListUtil.parse(raw).ifEmpty { fallbackCommand() }
                val executable = parsed.first().lowercase()
                val kind =
                    when {
                        executable.endsWith("wsl.exe") || executable.endsWith("wsl") -> Kind.WSL
                        executable.contains("powershell") || executable.contains("pwsh") -> Kind.POWERSHELL
                        executable.endsWith("cmd.exe") || executable.endsWith("cmd") -> Kind.CMD
                        else -> Kind.POSIX
                    }
                return Shell(kind, parsed)
            }

            private fun fallbackCommand(): List<String> {
                if (SystemInfo.isWindows) return listOf("powershell.exe")
                val environmentShell = System.getenv("SHELL")
                return if (environmentShell.isNullOrBlank()) listOf("/bin/sh") else listOf(environmentShell)
            }
        }
    }
}

internal class CommandOutputFile private constructor(
    val path: Path,
) : AutoCloseable {
    private val lock = Any()
    private val writer =
        Files.newBufferedWriter(
            path,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        )
    private var closed = false

    fun append(text: String) {
        synchronized(lock) {
            if (closed) return
            writer.append(text)
            writer.flush()
        }
    }

    fun digest(projectPath: String?): String =
        synchronized(lock) {
            if (!closed) writer.flush()
            val digest = ConsoleDigest(projectPath)
            Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
                val buffer = CharArray(8192)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    digest.append(String(buffer, 0, count))
                }
            }
            digest.snapshot()
        }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            writer.close()
        }
    }

    companion object {
        fun create(project: Project): CommandOutputFile {
            val directory =
                Path.of(PathManager.getSystemPath(), "aiCodingAgentChat", "commands", project.locationHash)
            Files.createDirectories(directory)
            return CommandOutputFile(directory.resolve("${System.currentTimeMillis()}-${UUID.randomUUID()}.log"))
        }

        internal fun at(path: Path): CommandOutputFile = CommandOutputFile(path)
    }
}
