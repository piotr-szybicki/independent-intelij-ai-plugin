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
            LineStatusTrackerDisplay(project),
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


    fun beginCapture() {
        captureDepth.incrementAndGet()
    }

    fun endCapture() {
        captureDepth.decrementAndGet()
    }

    private fun captureBaseline(document: Document) {
        if (captureDepth.get() <= 0) return

        if (CommandProcessor.getInstance().currentCommandName == null) return

        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        if (!file.isInLocalFileSystem) return

        var added = false
        synchronized(lock) {
            if (!baselines.containsKey(file)) {
                baselines[file] = document.text
                added = true
            }
        }

        ApplicationManager.getApplication().invokeLater({
            val baseline = synchronized(lock) { baselines[file] }
            if (baseline != null) display.show(file, baseline)
            if (added) notifyListeners()
        }, project.disposed)
    }


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
                        log.warn("Could not save ${file.path}: ${e.message}")
                    }
                }
            }
        }

        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) save.run() else application.invokeAndWait(save)
    }


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

        settle(file, baseline, document)
    }

    private fun Hunk.fits(baselineLineCount: Int, currentLineCount: Int): Boolean =
        oldStart in 0..oldEnd && oldEnd <= baselineLineCount &&
            newStart in 0..newEnd && newEnd <= currentLineCount

    private fun settle(file: VirtualFile, newBaseline: String, document: Document) {
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
