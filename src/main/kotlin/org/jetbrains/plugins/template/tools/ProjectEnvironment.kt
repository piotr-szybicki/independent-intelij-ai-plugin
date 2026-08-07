package org.jetbrains.plugins.template.tools

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo

/**
 * The facts about the machine that change what a correct answer looks like -- appended to the system
 * prompt once per conversation.
 *
 * Without this the model guesses, and guesses wrong in the ways that cost a turn: POSIX syntax typed
 * into PowerShell, forward slashes in a Windows path, `gradlew` instead of `gradlew.bat`. All of it
 * is knowable up front, so it is cheaper to state than to let a failed command discover it.
 */
object ProjectEnvironment {

    /** Reads terminal settings, so this must not run on the EDT. */
    fun describe(project: Project): String = buildString {
        appendLine("Environment:")
        appendLine("- Operating system: ${SystemInfo.OS_NAME} ${SystemInfo.OS_VERSION}")
        appendLine(
            "- Terminal shell: ${RunShellCommandTool.shellDialect(project)}. Write " +
                "run_shell_command input in this dialect, and use this platform's path separators " +
                "and script extensions in it.",
        )
        project.basePath?.let { appendLine("- Project root: $it") }
        append("- IDE: ${ApplicationInfo.getInstance().fullApplicationName}")
    }
}
