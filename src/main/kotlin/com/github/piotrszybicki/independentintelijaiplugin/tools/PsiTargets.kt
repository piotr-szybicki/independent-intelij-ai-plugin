package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import java.io.File

object PsiTargets {

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
        return ReadAction.computeBlocking<PsiElement?, RuntimeException> {
            val vf = resolveProjectFile(project, relativePath) ?: return@computeBlocking null
            val psiFile = PsiManager.getInstance(project).findFile(vf) ?: return@computeBlocking null
            val document = FileDocumentManager.getInstance().getDocument(vf) ?: return@computeBlocking null

            val lineIndex = line - 1
            if (lineIndex < 0 || lineIndex >= document.lineCount) return@computeBlocking null

            val lineStart = document.getLineStartOffset(lineIndex)
            val lineEnd = document.getLineEndOffset(lineIndex)

            var offset = lineStart
            while (offset <= lineEnd) {
                val leaf = psiFile.findElementAt(offset)
                if (leaf != null) {
                    val named = PsiTreeUtil.getParentOfType(leaf, PsiNamedElement::class.java, false)
                    if (named != null && named.name == symbolName) {
                        return@computeBlocking named
                    }
                    offset = leaf.textRange.endOffset.coerceAtLeast(offset + 1)
                } else {
                    offset++
                }
            }
            null
        }
    }

    fun resolveTarget(
        project: Project,
        relativePath: String,
        line: Int,
        symbolName: String,
        occurrence: Int = 1,
    ): PsiElement? = ReadAction.computeBlocking<PsiElement?, RuntimeException> {
        val resolved = occurrenceOffset(project, relativePath, line, symbolName, occurrence)?.let { offset ->
            val vf = resolveProjectFile(project, relativePath)
            val psiFile = vf?.let { PsiManager.getInstance(project).findFile(it) }
            psiFile?.findReferenceAt(offset)?.resolve()
        }
        resolved ?: resolveElement(project, relativePath, line, symbolName)
    }

    fun resolvePsiFile(project: Project, relativePath: String): PsiFile? =
        ReadAction.computeBlocking<PsiFile?, RuntimeException> {
            val vf = resolveProjectFile(project, relativePath) ?: return@computeBlocking null
            PsiManager.getInstance(project).findFile(vf)
        }

    fun isInProject(project: Project, element: PsiElement): Boolean {
        val vf = ReadAction.computeBlocking<VirtualFile?, RuntimeException> { element.containingFile?.virtualFile }
            ?: return false
        val basePath = project.basePath ?: return false
        return vf.path.startsWith(basePath)
    }

    private fun occurrenceOffset(
        project: Project,
        relativePath: String,
        line: Int,
        symbolName: String,
        occurrence: Int,
    ): Int? {
        if (symbolName.isEmpty() || occurrence < 1) return null
        val vf = resolveProjectFile(project, relativePath) ?: return null
        val document = FileDocumentManager.getInstance().getDocument(vf) ?: return null

        val lineIndex = line - 1
        if (lineIndex < 0 || lineIndex >= document.lineCount) return null
        val lineStart = document.getLineStartOffset(lineIndex)
        val lineText = document.getText(TextRange(lineStart, document.getLineEndOffset(lineIndex)))

        var found = 0
        var searchFrom = 0
        while (true) {
            val index = lineText.indexOf(symbolName, searchFrom)
            if (index < 0) return null
            if (isWholeWord(lineText, index, symbolName.length)) {
                found++
                if (found == occurrence) return lineStart + index
            }
            searchFrom = index + 1
        }
    }

    private fun isWholeWord(text: String, start: Int, length: Int): Boolean =
        !isIdentifierChar(text.getOrNull(start - 1)) && !isIdentifierChar(text.getOrNull(start + length))

    private fun isIdentifierChar(c: Char?): Boolean = c != null && (c.isLetterOrDigit() || c == '_' || c == '$')

    fun relativePath(project: Project, vf: VirtualFile): String {
        val basePath = project.basePath
        return if (basePath != null && vf.path.startsWith(basePath)) {
            vf.path.removePrefix(basePath).trimStart('/', '\\')
        } else {
            vf.path
        }
    }
}
