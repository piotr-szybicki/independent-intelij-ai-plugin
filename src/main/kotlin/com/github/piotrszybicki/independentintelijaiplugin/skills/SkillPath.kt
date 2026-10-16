package com.github.piotrszybicki.independentintelijaiplugin.skills

import com.intellij.openapi.project.Project
import java.io.File

object SkillPath {
    fun display(
        project: Project,
        file: File,
    ): String = display(project.basePath?.let(::File), file)

    fun display(
        base: File?,
        file: File,
    ): String {
        if (base == null) return file.path
        val root = runCatching { base.canonicalFile }.getOrDefault(base.absoluteFile)
        val prefix = root.path + File.separator
        if (!file.path.startsWith(prefix)) return file.path
        return file.path.removePrefix(prefix).replace(File.separatorChar, '/')
    }
}
