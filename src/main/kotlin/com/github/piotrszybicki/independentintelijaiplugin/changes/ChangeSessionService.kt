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

    fun flushToDisk() {
        saveNow(synchronized(lock) { baselines.keys.toList() })
    }

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

    fun changedFiles(): List<VirtualFile> = synchronized(lock) { baselines.keys.toList() }

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

    fun approveAll() {
        synchronized(lock) { baselines.clear() }
        display.clearAll()
        notifyListeners()
    }

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
