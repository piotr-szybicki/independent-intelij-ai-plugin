package com.github.piotrszybicki.independentintelijaiplugin.changes

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.github.piotrszybicki.independentintelijaiplugin.tools.PsiTargets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Holds the "what has the AI changed this session" state: one baseline text per file, captured the
 * first time a tool touches it.
 *
 * The baseline is grabbed in [DocumentListener.beforeDocumentChange], while the document still holds
 * its pre-change content. That is what makes this work for `rename_symbol` as well as the
 * single-file tools -- a refactoring fans out across files nobody knew about in advance, and this
 * still records every one of them without any per-tool knowledge.
 *
 * A session runs from the first captured file until [approveAll] or [revertAll]. Baselines live in
 * memory only, so closing the project behaves like an approve: the edits stay, the markers go.
 *
 * Edits reach disk as they are made -- see [flushToDisk]. Reverting is a matter of the baselines
 * held here, not of the file being left unwritten, so nothing is lost by saving: anything that reads
 * the project from outside the IDE (a compiler, a test runner, git) sees what the model actually
 * wrote rather than the last state the user happened to save.
 */
@Service(Service.Level.PROJECT)
class ChangeSessionService(private val project: Project) : Disposable {

    fun interface Listener {
        fun onSessionChanged(changedFileCount: Int)
    }

    private val log = Logger.getInstance(ChangeSessionService::class.java)
    private val lock = Any()
    private val baselines = LinkedHashMap<VirtualFile, String>()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val captureDepth = AtomicInteger(0)
    private val display: ChangeDisplay = CompositeChangeDisplay(
        listOf(
            // Gutter stripes plus the platform's own hunk popup.
            LineStatusTrackerDisplay(project),
            // Full-width line bands, the replaced lines above them, and the per-hunk buttons.
            InlineDiffDisplay(project, ::acceptHunk, ::rejectHunk),
        )
    )

    init {
        Disposer.register(this, display as Disposable)

        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun beforeDocumentChange(event: DocumentEvent) = captureBaseline(event.document)
            },
            this,
        )

        // Tools such as rename_symbol edit files that have no editor open. Their baseline is
        // recorded all the same, but there is nothing to draw on until the file is opened.
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    val baseline = synchronized(lock) { baselines[file] } ?: return
                    display.show(file, baseline)
                }
            },
        )
    }

    // --- capture ------------------------------------------------------------------------------

    /** Opens a capture window. Every [beginCapture] must be matched by an [endCapture]. */
    fun beginCapture() {
        captureDepth.incrementAndGet()
    }

    fun endCapture() {
        captureDepth.decrementAndGet()
    }

    private fun captureBaseline(document: Document) {
        if (captureDepth.get() <= 0) return

        // Every tool writes inside a named WriteCommandAction, so requiring a command in progress
        // keeps incidental document touches (index refreshes, save-time reformatting) out of the
        // session. It does not completely exclude the user typing in another file while a tool
        // runs; that window is short, because the tools hold the EDT via invokeAndWait for the
        // whole write, but it is not zero.
        if (CommandProcessor.getInstance().currentCommandName == null) return

        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        if (!file.isInLocalFileSystem) return

        var added = false
        synchronized(lock) {
            if (!baselines.containsKey(file)) {
                // beforeDocumentChange: this is still the pre-change content. Only read on the
                // first touch, so repeat edits to the same file do not re-copy the whole text.
                baselines[file] = document.text
                added = true
            }
        }

        // Redrawn after every captured change, not just the first: the second tool call to edit a
        // file leaves the previous render stale, because the baseline it was drawn against is no
        // longer what the document says.
        //
        // Deliberately not drawing inline: we are mid-event, and a tracker installed now would miss
        // the very change being dispatched. Drawing afterwards is correct anyway, because both
        // displays re-diff the whole document against the baseline.
        ApplicationManager.getApplication().invokeLater({
            val baseline = synchronized(lock) { baselines[file] }
            if (baseline != null) display.show(file, baseline)
            if (added) notifyListeners()
        }, project.disposed)
    }

    // --- writing through to disk ----------------------------------------------------------------

    /**
     * Saves every file changed this session that is still sitting unsaved in a document.
     *
     * Called once a tool call has finished rather than per document change: a refactoring touches
     * many files inside one command, and saving each one as it is edited would mean writing some
     * files several times for a single tool call.
     *
     * The session's markers and baselines are unaffected -- saving is not approving. Accept, reject
     * and revert all keep working afterwards, because they replay the baseline into the document and
     * that write is itself saved.
     */
    fun flushToDisk() {
        saveNow(synchronized(lock) { baselines.keys.toList() })
    }

    /**
     * Writes [files] out now. Safe to call from either thread: saving is an EDT operation, and the
     * tools reach here from a pooled thread while the UI reaches it from the EDT already.
     */
    private fun saveNow(files: Collection<VirtualFile>) {
        if (files.isEmpty()) return
        val documentManager = FileDocumentManager.getInstance()

        val save = Runnable {
            WriteAction.run<RuntimeException> {
                for (file in files) {
                    val document = documentManager.getDocument(file) ?: continue
                    if (!documentManager.isDocumentUnsaved(document)) continue
                    try {
                        documentManager.saveDocument(document)
                    } catch (e: Exception) {
                        // A read-only or externally-deleted file. The edit is still in the document
                        // and still revertible; only the write-through failed.
                        log.warn("Could not save ${file.path}: ${e.message}")
                    }
                }
            }
        }

        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) save.run() else application.invokeAndWait(save)
    }

    // --- session state ------------------------------------------------------------------------

    val changedFileCount: Int
        get() = synchronized(lock) { baselines.size }

    /** The files changed this session, in the order they were touched. */
    fun changedFiles(): List<VirtualFile> = synchronized(lock) { baselines.keys.toList() }

    /** Project-relative paths of the files changed this session, in the order they were touched. */
    fun changedPaths(): List<String> = changedFiles().map { PsiTargets.relativePath(project, it) }

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        val count = changedFileCount
        ApplicationManager.getApplication().invokeLater({
            listeners.forEach { it.onSessionChanged(count) }
        }, project.disposed)
    }

    // --- approve / revert ---------------------------------------------------------------------

    /**
     * Accepts one hunk: the document is untouched, and the baseline moves forward to match it so
     * that region stops being reported as changed. Call on the EDT.
     */
    fun acceptHunk(file: VirtualFile, hunk: Hunk) {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return
        val baseline = synchronized(lock) { baselines[file] } ?: return

        val baselineLines = baseline.split("\n")
        val currentLines = document.text.split("\n")
        if (!hunk.fits(baselineLines.size, currentLines.size)) {
            log.info("Skipping stale accept on ${file.path}: $hunk no longer fits the file")
            return
        }

        val merged = buildList {
            addAll(baselineLines.subList(0, hunk.oldStart))
            addAll(currentLines.subList(hunk.newStart, hunk.newEnd))
            addAll(baselineLines.subList(hunk.oldEnd, baselineLines.size))
        }.joinToString("\n")

        settle(file, merged, document)
    }

    /**
     * Rejects one hunk: the document goes back to what the baseline says for that region, leaving
     * the rest of the session's changes in place. Call on the EDT.
     */
    fun rejectHunk(file: VirtualFile, hunk: Hunk) {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return
        val baseline = synchronized(lock) { baselines[file] } ?: return
        if (!document.isWritable) return

        val baselineLines = baseline.split("\n")
        val currentLines = document.text.split("\n")
        if (!hunk.fits(baselineLines.size, currentLines.size)) {
            log.info("Skipping stale reject on ${file.path}: $hunk no longer fits the file")
            return
        }

        val restored = buildList {
            addAll(currentLines.subList(0, hunk.newStart))
            addAll(baselineLines.subList(hunk.oldStart, hunk.oldEnd))
            addAll(currentLines.subList(hunk.newEnd, currentLines.size))
        }.joinToString("\n")

        WriteCommandAction.runWriteCommandAction(project, "Reject AI Change", null, Runnable {
            document.setText(restored)
        })

        // The baseline is unchanged -- rejecting is the document moving back towards it.
        settle(file, baseline, document)
    }

    private fun Hunk.fits(baselineLineCount: Int, currentLineCount: Int): Boolean =
        oldStart in 0..oldEnd && oldEnd <= baselineLineCount &&
            newStart in 0..newEnd && newEnd <= currentLineCount

    /**
     * Records [newBaseline] for [file] and redraws, dropping the file from the session entirely once
     * the document and the baseline agree.
     */
    private fun settle(file: VirtualFile, newBaseline: String, document: Document) {
        // Rejecting a hunk rewrote the document, so disk is now behind. Accepting one did not touch
        // it, in which case this finds nothing unsaved and does nothing.
        saveNow(listOf(file))

        val done = document.text == newBaseline
        synchronized(lock) {
            if (done) baselines.remove(file) else baselines[file] = newBaseline
        }
        if (done) display.clear(file) else display.show(file, newBaseline)
        notifyListeners()
    }

    /**
     * Accepts everything: the markers go away and the documents are left exactly as they are.
     * Call on the EDT.
     */
    fun approveAll() {
        synchronized(lock) { baselines.clear() }
        display.clearAll()
        notifyListeners()
    }

    /**
     * Restores every file to its baseline, as one undoable command.
     *
     * Deliberately not built on [com.intellij.openapi.command.undo.UndoManager]: the undo stack is
     * ordered and interleaved with the user's own edits, so replaying the agent's commands out of
     * order is fragile and fails outright once the user has typed in between. Writing back the
     * stored text is deterministic.
     *
     * Call on the EDT. Returns the project-relative paths that could not be restored.
     */
    fun revertAll(): List<String> {
        val snapshot = synchronized(lock) { LinkedHashMap(baselines) }
        if (snapshot.isEmpty()) return emptyList()

        val failed = mutableListOf<String>()
        val documentManager = FileDocumentManager.getInstance()

        WriteCommandAction.runWriteCommandAction(project, "Revert AI Changes", null, Runnable {
            for ((file, baseline) in snapshot) {
                val document = documentManager.getDocument(file)
                if (document == null || !document.isWritable) {
                    failed.add(PsiTargets.relativePath(project, file))
                    continue
                }
                if (document.text != baseline) {
                    document.setText(baseline)
                }
            }
        })

        // The edits were written to disk as they were made, so restoring the document is only half
        // the revert -- without this, disk keeps the version being undone.
        saveNow(snapshot.keys)

        if (failed.isNotEmpty()) {
            log.warn("Could not revert ${failed.size} file(s): ${failed.joinToString()}")
        }

        synchronized(lock) { baselines.clear() }
        display.clearAll()
        notifyListeners()
        return failed
    }

    override fun dispose() {
        synchronized(lock) { baselines.clear() }
        listeners.clear()
    }

    companion object {
        fun getInstance(project: Project): ChangeSessionService = project.getService(ChangeSessionService::class.java)
    }
}
