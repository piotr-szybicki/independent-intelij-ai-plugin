package com.github.piotrszybicki.independentintelijaiplugin.agents

import com.github.piotrszybicki.independentintelijaiplugin.tools.ChatWorkingDirectory
import com.intellij.openapi.project.Project
import java.nio.file.Path

object AgentPath {
    fun display(
        project: Project,
        path: Path,
    ): String {
        val root = ChatWorkingDirectory.path(project) ?: return path.toString()
        val relative = runCatching { Path.of(root).relativize(path) }.getOrNull() ?: return path.toString()
        return relative.toString().replace('\\', '/')
    }
}
