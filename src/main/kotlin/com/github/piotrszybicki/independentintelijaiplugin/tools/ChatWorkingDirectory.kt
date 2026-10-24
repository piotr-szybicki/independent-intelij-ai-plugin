package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import java.io.File

/** Chats run sequentially; switching is blocked until the current reply finishes. */
object ChatWorkingDirectory {
    private val ROOT = Key.create<String>("aicodingagent.chatWorkingDirectory")

    fun path(project: Project): String? = project.getUserData(ROOT) ?: project.basePath

    fun select(
        project: Project,
        path: String?,
    ) {
        project.putUserData(ROOT, path?.let { File(it).canonicalPath.replace('\\', '/') })
    }

    fun contains(
        project: Project,
        candidate: String,
    ): Boolean {
        val root = path(project)?.let(::File) ?: return false
        return ProjectPaths.resolve(root, candidate) != null
    }
}
