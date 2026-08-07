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

    /**
     * Resolves what the model is pointing at, whether it named a declaration or a *use* of one.
     *
     * [resolveElement] only ever finds declarations -- it walks up to a named element whose name
     * matches -- so a call site resolves to nothing at all. Reading code, the model sees uses far
     * more often than definitions, so the reference is tried first and the declaration walk is the
     * fallback. That makes "the `execute` on this line" a usable way to name a symbol either way.
     *
     * [occurrence] picks between repeats of the name on one line, 1-based and left to right.
     *
     * The result may live outside the project -- a library or SDK class -- which is exactly what
     * makes this useful for reading. Callers that go on to *modify* it must check [isInProject].
     */
    fun resolveTarget(
        project: Project,
        relativePath: String,
        line: Int,
        symbolName: String,
        occurrence: Int = 1,
    ): PsiElement? = ReadAction.compute<PsiElement?, RuntimeException> {
        val resolved = occurrenceOffset(project, relativePath, line, symbolName, occurrence)?.let { offset ->
            val vf = resolveProjectFile(project, relativePath)
            val psiFile = vf?.let { PsiManager.getInstance(project).findFile(it) }
            psiFile?.findReferenceAt(offset)?.resolve()
        }
        resolved ?: resolveElement(project, relativePath, line, symbolName)
    }

    fun resolvePsiFile(project: Project, relativePath: String): PsiFile? =
        ReadAction.compute<PsiFile?, RuntimeException> {
            val vf = resolveProjectFile(project, relativePath) ?: return@compute null
            PsiManager.getInstance(project).findFile(vf)
        }

    /**
     * The first element on [line] that is not whitespace.
     *
     * Unlike [resolveTarget] this deliberately does *not* follow references: it answers "what is
     * written here", which is what the run-configuration producers want. They walk up the tree
     * themselves, so landing anywhere inside the declaration is enough.
     */
    fun elementAtLine(project: Project, relativePath: String, line: Int): PsiElement? =
        ReadAction.compute<PsiElement?, RuntimeException> {
            val vf = resolveProjectFile(project, relativePath) ?: return@compute null
            val psiFile = PsiManager.getInstance(project).findFile(vf) ?: return@compute null
            val document = FileDocumentManager.getInstance().getDocument(vf) ?: return@compute null

            val lineIndex = line - 1
            if (lineIndex < 0 || lineIndex >= document.lineCount) return@compute null
            val lineStart = document.getLineStartOffset(lineIndex)
            val lineEnd = document.getLineEndOffset(lineIndex)

            var offset = lineStart
            while (offset < lineEnd) {
                val leaf = psiFile.findElementAt(offset)
                if (leaf != null && leaf.text.isNotBlank()) return@compute leaf
                offset = leaf?.textRange?.endOffset?.coerceAtLeast(offset + 1) ?: (offset + 1)
            }
            // A blank line still belongs to something -- the enclosing class, usually.
            psiFile.findElementAt(lineStart) ?: psiFile
        }

    /** True when [element] is declared in a file under the project root, and so is ours to change. */
    fun isInProject(project: Project, element: PsiElement): Boolean {
        val vf = ReadAction.compute<VirtualFile?, RuntimeException> { element.containingFile?.virtualFile }
            ?: return false
        val basePath = project.basePath ?: return false
        return vf.path.startsWith(basePath)
    }

    /**
     * Offset of the [occurrence]th whole-word appearance of [symbolName] on [line]. Whole-word so
     * that asking about `session` does not land inside `sessionService`.
     */
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
