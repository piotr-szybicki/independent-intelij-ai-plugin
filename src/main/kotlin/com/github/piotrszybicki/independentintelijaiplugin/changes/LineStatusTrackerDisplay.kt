package com.github.piotrszybicki.independentintelijaiplugin.changes

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ex.LineStatusMarkerPopupRenderer
import com.intellij.openapi.vcs.ex.SimpleLineStatusTracker
import com.intellij.openapi.vfs.VirtualFile

class LineStatusTrackerDisplay(private val project: Project) : ChangeDisplay, Disposable {

    private val log = Logger.getInstance(LineStatusTrackerDisplay::class.java)
    private val trackers = mutableMapOf<VirtualFile, SimpleLineStatusTracker>()

    override fun show(file: VirtualFile, baseline: String) {
        val existing = trackers[file]
        if (existing != null) {
            if (!existing.isReleased) {
                existing.setBaseRevision(baseline)
                return
            }
            trackers.remove(file)
        }

        val document = FileDocumentManager.getInstance().getDocument(file) ?: return
        val tracker = try {
            @Suppress("DEPRECATION")
            SimpleLineStatusTracker(project, document) { LineStatusMarkerPopupRenderer(it) }
        } catch (e: Throwable) {
            log.warn("Could not create a line status tracker for ${file.path}", e)
            return
        }

        tracker.setBaseRevision(baseline)
        trackers[file] = tracker
    }

    override fun clear(file: VirtualFile) {
        trackers.remove(file)?.release()
    }

    override fun clearAll() {
        trackers.values.forEach { it.release() }
        trackers.clear()
    }

    override fun dispose() {
        clearAll()
    }
}
