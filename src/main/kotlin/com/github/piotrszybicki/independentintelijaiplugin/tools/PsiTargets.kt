package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import java.io.File

object PsiTargets {

    /**
     * Resolves a project-relative path, rejecting anything that escapes the project root. The file
     * need not exist -- this is the form `write_file` needs, since it may be creating one.
     */
    fun resolveProjectPath(project: Project, relativePath: String): File? {
        val basePath = project.basePath ?: return null
        val baseDir = File(basePath).canonicalFile
        val target = File(baseDir, relativePath).canonicalFile
        if (!target.path.startsWith(baseDir.path + File.separator) && target.path != baseDir.path) {
            return null
        }
        return target
    }

    fun resolveProjectFile(project: Project, relativePath: String): VirtualFile? {
        val target = resolveProjectPath(project, relativePath) ?: return null
        return LocalFileSystem.getInstance().findFileByIoFile(target)
    }

    fun resolveElement(project: Project, relativePath: String, line: Int, symbolName: String): PsiElement? {
        return ReadAction.compute<PsiElement?, RuntimeException> {
            val vf = resolveProjectFile(project, relativePath) ?: return@compute null
            val psiFile = PsiManager.getInstance(project).findFile(vf) ?: return@compute null
            val document = FileDocumentManager.getInstance().getDocument(vf) ?: return@compute null

            val lineIndex = line - 1
            if (lineIndex < 0 || lineIndex >= document.lineCount) return@compute null

            val lineStart = document.getLineStartOffset(lineIndex)
            val lineEnd = document.getLineEndOffset(lineIndex)

            var offset = lineStart
            while (offset <= lineEnd) {
                val leaf = psiFile.findElementAt(offset)
                if (leaf != null) {
                    val named = PsiTreeUtil.getParentOfType(leaf, PsiNamedElement::class.java, false)
                    if (named != null && named.name == symbolName) {
                        return@compute named
                    }
                    offset = leaf.textRange.endOffset.coerceAtLeast(offset + 1)
                } else {
                    offset++
                }
            }
            null
        }
    }

    fun relativePath(project: Project, vf: VirtualFile): String {
        val basePath = project.basePath
        return if (basePath != null && vf.path.startsWith(basePath)) {
            vf.path.removePrefix(basePath).trimStart('/', '\\')
        } else {
            vf.path
        }
    }
}
