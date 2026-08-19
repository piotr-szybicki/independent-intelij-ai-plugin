package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile

object ProjectFiles {

    private val ALWAYS_SKIP = setOf(".git", ".hg", ".svn", ".idea", "node_modules", "__pycache__")

    fun isSkipped(project: Project, vf: VirtualFile): Boolean {
        if (vf.name in ALWAYS_SKIP) return true
        return ReadAction.computeBlocking<Boolean, RuntimeException> {
            val index = ProjectRootManager.getInstance(project).fileIndex
            index.isExcluded(vf) || index.isUnderIgnored(vf)
        }
    }

    fun walk(
        project: Project,
        root: VirtualFile,
        maxDepth: Int,
        visit: (file: VirtualFile, depth: Int) -> Boolean,
    ) {
        walkFrom(project, root, 1, maxDepth, visit)
    }

    private fun walkFrom(
        project: Project,
        dir: VirtualFile,
        depth: Int,
        maxDepth: Int,
        visit: (VirtualFile, Int) -> Boolean,
    ): Boolean {
        if (depth > maxDepth) return true

        val children = ReadAction.computeBlocking<List<VirtualFile>, RuntimeException> {
            dir.children?.toList().orEmpty()
        }.sortedWith(compareByDescending<VirtualFile> { it.isDirectory }.thenBy { it.name.lowercase() })

        for (child in children) {
            if (isSkipped(project, child)) continue
            if (!visit(child, depth)) return false
            if (child.isDirectory && !walkFrom(project, child, depth + 1, maxDepth, visit)) return false
        }
        return true
    }
}
