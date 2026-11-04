package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.intellij.openapi.project.Project
import java.io.File

/** Chats run sequentially; switching is blocked until the current reply finishes. */
object ChatWorkingDirectory {
    fun path(project: Project): String? = project.basePath

    fun contains(
        project: Project,
        candidate: String,
    ): Boolean {
        val root = path(project)?.let(::File) ?: return false
        return ProjectPaths.resolve(root, candidate) != null
    }
}
