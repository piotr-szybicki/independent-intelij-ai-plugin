package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo

object ProjectEnvironment {

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
