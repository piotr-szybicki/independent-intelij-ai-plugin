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

/**
 * Walks a tree of files and does something to every comment a [CommentPlan] claims.
 *
 * Three passes, and the split between them is the whole design. The scan runs on a background thread
 * under read actions, so the IDE stays usable while a project is walked. The plan is then asked what
 * to do with each comment with no lock held at all, which is what lets [StoreJavadoc] wait on a
 * database without freezing the editor. Only then do the writes land, in a single write command on
 * the EDT -- which is what makes the whole sweep, however many files it touched, one press of Undo.
 *
 * The gap between scan and write is covered by remembering the exact text each edit expects to find.
 * A file edited in between -- by the user, by a formatter, by anything -- fails that comparison and
 * is left alone rather than having a stale offset range cut out of the middle of it.
 *
 * Nothing is saved to disk here. The edits sit in the documents like any other unsaved change, so
 * the result can be read, undone or committed the way an editor change would be.
 */
class CommentSweep(
    private val project: Project,
    private val roots: List<VirtualFile>,
    private val plan: CommentPlan,
    /** Count what the plan would claim, ask it for nothing, change nothing. */
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

        /** Shared by every document change, so one Undo takes back the whole sweep. */
        const val UNDO_GROUP = "AICodingAgent.CommentSweep"
    }

    /** What the sweep found, and what it managed to do about it. */
    data class Report(
        val scannedFiles: Int,
        val comments: Int,
        val filesWithComments: Int,
        val applied: Int,
        val changedFiles: Int,
        /** Files that held comments and were left as they were -- read-only, or edited mid-sweep. */
        val skipped: List<String>,
    )

    /** One comment worth of text to change, and the text that has to still be there for it to happen. */
    private data class Cut(val start: Int, val end: Int, val expected: String, val replacement: String)

    private data class FileFinds(val file: VirtualFile, val finds: List<FoundComment>)

    private data class FileEdit(val file: VirtualFile, val cuts: List<Cut>)

    /**
     * Runs the sweep. Called from a background thread; [indicator] is polled for cancellation.
     *
     * Anything the plan throws comes out of here -- a database that cannot be reached is the sweep's
     * failure, not one file's, and carrying on would leave half the comments in the code and half in
     * a table.
     */
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
     * The comments one file holds that the plan wants, or null when it holds none.
     *
     * They come from the file PSI rather than from a search of its text, which is what keeps a
     * doc-comment opener inside a string literal out of the results -- and what makes the sweep work
     * the same in every language the IDE can parse, since [PsiComment] is what they all report a
     * comment as. A file the IDE has no parser for is plain text with no comments in it, so it falls
     * out here on its own.
     */
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

    /**
     * Asks the plan what each comment becomes.
     *
     * No lock is held here on purpose -- see the class comment. The progress bar moves per file
     * because a plan that inserts a row per comment is the slow part of the whole sweep.
     */
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

    /**
     * Applies every file's edits in one write command.
     *
     * Back to front within a file, so that changing one comment does not move the offsets of the
     * ones still to come.
     */
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

    /**
     * Writes the sweep through to disk, the way `ChangeSessionService` does for the agent's edits.
     *
     * A sweep leaves hundreds of documents modified at once, and leaving them unsaved is not the
     * harmless "it is only in the editor" it sounds like: everything outside the IDE -- a build, a
     * `git diff`, another IDE with the same project open -- goes on reading files that no longer say
     * what the editor says, and the platform's own persisted editor markup is keyed to what is on
     * disk, so it comes back stale against a document that has since become shorter.
     *
     * Undo is unaffected: it works on the document, and a saved file is undone and saved again.
     */
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
