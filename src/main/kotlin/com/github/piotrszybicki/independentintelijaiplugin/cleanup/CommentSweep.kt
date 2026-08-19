package com.github.piotrszybicki.independentintelijaiplugin.cleanup

import com.github.piotrszybicki.independentintelijaiplugin.tools.ProjectFiles
import com.github.piotrszybicki.independentintelijaiplugin.tools.PsiTargets
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil

class CommentSweep(
    private val project: Project,
    private val roots: List<VirtualFile>,
    private val plan: CommentPlan,
    private val dryRun: Boolean,
) {

    private companion object {
        const val MAX_DEPTH = 50

        const val MAX_FILE_LENGTH = 1_000_000L

        const val UNDO_GROUP = "AICodingAgent.CommentSweep"
    }

    data class Report(
        val scannedFiles: Int,
        val comments: Int,
        val filesWithComments: Int,
        val applied: Int,
        val changedFiles: Int,
        val skipped: List<String>,
    )

    private data class Cut(val start: Int, val end: Int, val expected: String, val replacement: String)

    private data class FileFinds(val file: VirtualFile, val finds: List<FoundComment>)

    private data class FileEdit(val file: VirtualFile, val cuts: List<Cut>)

    fun run(indicator: ProgressIndicator): Report {
        indicator.text = "Collecting files"
        val files = collectFiles(indicator)

        indicator.text = "Looking for comments"
        val found = mutableListOf<FileFinds>()
        for ((index, file) in files.withIndex()) {
            indicator.checkCanceled()
            indicator.fraction = index.toDouble() / files.size.coerceAtLeast(1)
            indicator.text2 = PsiTargets.relativePath(project, file)
            scan(file)?.let { found.add(it) }
        }

        val comments = found.sumOf { it.finds.size }
        if (dryRun || found.isEmpty()) {
            return Report(files.size, comments, found.size, 0, 0, emptyList())
        }

        indicator.text = "Preparing $comments comment(s)"
        indicator.text2 = ""
        val edits = planEdits(found, indicator)

        indicator.isIndeterminate = true
        indicator.text = "Applying"
        val applied = applyCuts(edits)
        return applied.copy(scannedFiles = files.size, comments = comments, filesWithComments = found.size)
    }

    private fun collectFiles(indicator: ProgressIndicator): List<VirtualFile> {
        val files = LinkedHashSet<VirtualFile>()
        for (root in roots) {
            indicator.checkCanceled()
            if (!root.isDirectory) {
                files.add(root)
                continue
            }
            ProjectFiles.walk(project, root, MAX_DEPTH) { file, _ ->
                indicator.checkCanceled()
                if (!file.isDirectory) files.add(file)
                true
            }
        }
        return files.toList()
    }

    private fun scan(file: VirtualFile): FileFinds? =
        ReadAction.computeBlocking<FileFinds?, RuntimeException> {
            if (file.length > MAX_FILE_LENGTH || file.fileType.isBinary) return@computeBlocking null
            val psiFile = PsiManager.getInstance(project).findFile(file) ?: return@computeBlocking null
            val text = psiFile.text ?: return@computeBlocking null

            val finds = PsiTreeUtil.collectElementsOfType(psiFile, PsiComment::class.java)
                .filter { plan.wants(it.text) }
                .map { FoundComment(text, it.textRange.startOffset, it.textRange.endOffset) }

            if (finds.isEmpty()) null else FileFinds(file, finds)
        }

    private fun planEdits(found: List<FileFinds>, indicator: ProgressIndicator): List<FileEdit> {
        val edits = mutableListOf<FileEdit>()
        for ((index, file) in found.withIndex()) {
            indicator.checkCanceled()
            indicator.fraction = index.toDouble() / found.size.coerceAtLeast(1)
            indicator.text2 = PsiTargets.relativePath(project, file.file)

            val cuts = file.finds.mapNotNull { find ->
                plan.edit(find)?.let { edit ->
                    Cut(edit.start, edit.end, find.fileText.substring(edit.start, edit.end), edit.replacement)
                }
            }
            if (cuts.isNotEmpty()) edits.add(FileEdit(file.file, cuts))
        }
        return edits
    }

    private fun applyCuts(edits: List<FileEdit>): Report {
        var applied = 0
        var changedFiles = 0
        val skipped = mutableListOf<String>()
        val changed = mutableListOf<VirtualFile>()

        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project, plan.commandName, UNDO_GROUP, Runnable {
                for (edit in edits) {
                    val path = PsiTargets.relativePath(project, edit.file)
                    val document = FileDocumentManager.getInstance().getDocument(edit.file)
                    if (document == null || !document.isWritable) {
                        skipped.add(path)
                        continue
                    }

                    var cutsHere = 0
                    for (cut in edit.cuts.sortedByDescending { it.start }) {
                        if (cut.end > document.textLength) continue
                        if (document.getText(TextRange(cut.start, cut.end)) != cut.expected) continue
                        document.replaceString(cut.start, cut.end, cut.replacement)
                        cutsHere++
                    }

                    if (cutsHere == 0) {
                        skipped.add(path)
                    } else {
                        applied += cutsHere
                        changedFiles++
                        changed.add(edit.file)
                    }
                }
                PsiDocumentManager.getInstance(project).commitAllDocuments()
            })

            // After the command rather than inside it: saving is not part of the change, and a
            // command that saves is a command Undo has to unpick around.
            saveNow(changed)
        }

        return Report(0, 0, 0, applied, changedFiles, skipped)
    }

    private fun saveNow(files: List<VirtualFile>) {
        if (files.isEmpty()) return
        val documentManager = FileDocumentManager.getInstance()

        WriteAction.run<RuntimeException> {
            for (file in files) {
                val document = documentManager.getDocument(file) ?: continue
                if (!documentManager.isDocumentUnsaved(document)) continue
                try {
                    documentManager.saveDocument(document)
                } catch (e: Exception) {
                    // Read-only, or deleted underneath us. The edit is still in the document and
                    // still revertible; only the write-through failed.
                    log.warn("Could not save ${file.path}: ${e.message}")
                }
            }
        }
    }

    private val log = Logger.getInstance(CommentSweep::class.java)
}
