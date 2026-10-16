package com.github.piotrszybicki.independentintelijaiplugin.agents

import com.intellij.openapi.project.Project
import java.nio.file.Path

object AgentPath {
    fun display(
        project: Project,
        path: Path,
    ): String {
        val root = project.basePath ?: return path.toString()
        val relative = runCatching { Path.of(root).relativize(path) }.getOrNull() ?: return path.toString()
        return relative.toString().replace('\\', '/')
    }
}
