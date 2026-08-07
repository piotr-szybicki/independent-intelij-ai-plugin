package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile

/**
 * Walking the project's files, shared by the tools that need to enumerate rather than search.
 *
 * Deliberately goes through the VFS instead of `java.io.File`: the VFS is what the rest of the
 * plugin edits through, so a file created earlier in the same conversation is visible here even
 * though nothing has been written to disk yet. It also lets [isSkipped] ask the project model what
 * is build output rather than guessing from directory names.
 */
object ProjectFiles {

    /** Names worth skipping even when the project model has no opinion -- VCS metadata, dependency dumps. */
    private val ALWAYS_SKIP = setOf(".git", ".hg", ".svn", ".idea", "node_modules", "__pycache__")

    /**
     * Whether the walk should not descend into [vf]. Excluded roots (build output, generated
     * sources) are the ones that matter: they are usually far larger than the source tree and
     * never what the caller meant.
     */
    fun isSkipped(project: Project, vf: VirtualFile): Boolean {
        if (vf.name in ALWAYS_SKIP) return true
        return ReadAction.compute<Boolean, RuntimeException> {
            val index = ProjectRootManager.getInstance(project).fileIndex
            index.isExcluded(vf) || index.isUnderIgnored(vf)
        }
    }

    /**
     * Depth-first walk from [root], visiting entries in a stable order (directories first, then
     * files, each alphabetical) so repeated calls read the same way.
     *
     * [maxDepth] is relative to [root]: 1 lists its immediate children. [visit] returns false to
     * stop the walk, which is how callers enforce a result cap without walking the whole tree.
     */
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

        val children = ReadAction.compute<List<VirtualFile>, RuntimeException> {
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
