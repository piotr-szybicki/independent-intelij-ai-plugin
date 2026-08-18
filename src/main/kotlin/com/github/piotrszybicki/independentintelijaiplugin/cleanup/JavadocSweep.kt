package com.github.piotrszybicki.independentintelijaiplugin.cleanup

import com.github.piotrszybicki.independentintelijaiplugin.tools.ProjectFiles
import com.github.piotrszybicki.independentintelijaiplugin.tools.PsiTargets
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil

/**
 * Takes the Javadoc comments out of a tree of files.
 *
 * Two passes rather than one, and for a reason: the scan runs on a background thread under read
 * actions, so the IDE stays usable while a whole project is walked, and the writes then land in a
 * single write command on the EDT -- which is what makes the whole sweep, however many files it
 * touched, one press of Undo.
 *
 * The gap between the two passes is covered by remembering the exact text each cut expects to find.
 * A file edited in between -- by the user, by a formatter, by anything -- fails that comparison and
 * is left alone rather than having a stale offset range cut out of the middle of it.
 *
 * Nothing is saved to disk here. The edits sit in the documents like any other unsaved change, so
 * the result can be read, undone or committed the way an editor change would be.
 */
class JavadocSweep(
    private val project: Project,
    private val roots: List<VirtualFile>,
    /** Only the empty stubs -- see [JavadocComments.isBlank] -- rather than every Javadoc comment. */
    private val onlyBlank: Boolean,
    /** Count what would go, change nothing. */
    private val dryRun: Boolean,
) {

    private companion object {
        /** Deep enough for any source tree; a bound rather than a limit anyone should reach. */
        const val MAX_DEPTH = 50

        /**
         * Files past this are not source and are expensive to hold as text -- a generated parser, a
         * minified bundle, a data dump that happens to live under a content root.
         */
        const val MAX_FILE_LENGTH = 1_000_000L

        const val COMMAND_NAME = "Remove Javadoc Comments"

        /** Shared by every document change, so one Undo takes back the whole sweep. */
        const val UNDO_GROUP = "AICodingAgent.RemoveJavadoc"
    }

    /** What the sweep found, and what it managed to do about it. */
    data class Report(
        val scannedFiles: Int,
        val comments: Int,
        val filesWithComments: Int,
        val removed: Int,
        val changedFiles: Int,
        /** Files that held comments and were left as they were -- read-only, or edited mid-sweep. */
        val skipped: List<String>,
    )

    /** One comment worth of text to cut, and the text that has to still be there for it to happen. */
    private data class Cut(val start: Int, val end: Int, val text: String)

    private data class FileEdit(val file: VirtualFile, val cuts: List<Cut>)

    /** Runs the sweep. Called from a background thread; [indicator] is polled for cancellation. */
    fun run(indicator: ProgressIndicator): Report {
        indicator.text = "Collecting files"
        val files = collectFiles(indicator)

        indicator.text = "Looking for Javadoc comments"
        val edits = mutableListOf<FileEdit>()
        for ((index, file) in files.withIndex()) {
            indicator.checkCanceled()
            indicator.fraction = index.toDouble() / files.size.coerceAtLeast(1)
            indicator.text2 = PsiTargets.relativePath(project, file)
            scan(file)?.let { edits.add(it) }
        }

        val comments = edits.sumOf { it.cuts.size }
        if (dryRun || edits.isEmpty()) {
            return Report(files.size, comments, edits.size, 0, 0, emptyList())
        }

        indicator.text = "Removing $comments comment(s)"
        indicator.text2 = ""
        indicator.isIndeterminate = true
        val applied = applyCuts(edits)
        return applied.copy(scannedFiles = files.size, comments = comments, filesWithComments = edits.size)
    }

    /**
     * Every file under [roots], skipping what [ProjectFiles] considers not ours -- build output, VCS
     * metadata, excluded roots. A root that is itself a file is taken as given: it was pointed at,
     * so it is not for the walk rules to drop it.
     */
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

    /**
     * The cuts one file needs, or null when it needs none.
     *
     * Comments come from the file PSI rather than from a search of its text, which is what keeps a
     * doc-comment opener inside a string literal out of the results -- and what makes the sweep work
     * the same in every language the IDE can parse, since [PsiComment] is what they all report a
     * comment as. A file the IDE has no parser for is plain text with no comments in it, so it falls
     * out here on its own.
     */
    private fun scan(file: VirtualFile): FileEdit? =
        ReadAction.compute<FileEdit?, RuntimeException> {
            if (file.length > MAX_FILE_LENGTH || file.fileType.isBinary) return@compute null
            val psiFile = PsiManager.getInstance(project).findFile(file) ?: return@compute null
            val text = psiFile.text ?: return@compute null

            val cuts = PsiTreeUtil.collectElementsOfType(psiFile, PsiComment::class.java)
                .filter { wanted(it.text) }
                .map { comment ->
                    val range = comment.textRange
                    val removal = JavadocComments.removalFor(text, range.startOffset, range.endOffset)
                    Cut(removal.start, removal.end, text.substring(removal.start, removal.end))
                }

            if (cuts.isEmpty()) null else FileEdit(file, cuts)
        }

    private fun wanted(commentText: String): Boolean =
        if (onlyBlank) JavadocComments.isBlank(commentText) else JavadocComments.isJavadoc(commentText)

    /**
     * Applies every file's cuts in one write command.
     *
     * Back to front within a file, so that cutting one comment does not move the offsets of the ones
     * still to come.
     */
    private fun applyCuts(edits: List<FileEdit>): Report {
        var removed = 0
        var changedFiles = 0
        val skipped = mutableListOf<String>()

        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project, COMMAND_NAME, UNDO_GROUP, Runnable {
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
                        if (document.getText(TextRange(cut.start, cut.end)) != cut.text) continue
                        document.deleteString(cut.start, cut.end)
                        cutsHere++
                    }

                    if (cutsHere == 0) {
                        skipped.add(path)
                    } else {
                        removed += cutsHere
                        changedFiles++
                    }
                }
                PsiDocumentManager.getInstance(project).commitAllDocuments()
            })
        }

        return Report(0, 0, 0, removed, changedFiles, skipped)
    }
}
