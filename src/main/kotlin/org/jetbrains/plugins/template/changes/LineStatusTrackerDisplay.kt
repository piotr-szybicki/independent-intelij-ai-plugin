package org.jetbrains.plugins.template.changes

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ex.LineStatusMarkerPopupRenderer
import com.intellij.openapi.vcs.ex.SimpleLineStatusTracker
import com.intellij.openapi.vfs.VirtualFile

/**
 * Draws the session's changes with the platform's own line status trackers, so the gutter stripes,
 * the scrollbar marks and the click-through hunk popup (with its per-hunk rollback) all look and
 * behave exactly like the ones VCS puts on a modified file.
 *
 * [SimpleLineStatusTracker] is the VCS-free variant: it takes a baseline text directly instead of
 * asking a version control system for one, so no `com.intellij.modules.vcs` dependency is needed.
 */
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
            // The two-argument constructor installs no renderer at all -- it only records the
            // virtual file, so the tracker computes ranges that nothing ever paints. The renderer
            // has to be supplied here. LineStatusMarkerPopupRenderer is the concrete one that draws
            // the gutter stripes and opens the hunk popup (old text, rollback, show diff, copy).
            SimpleLineStatusTracker(project, document) { LineStatusMarkerPopupRenderer(it) }
        } catch (e: Throwable) {
            // openapi.vcs.ex is semi-internal; if it ever moves, the session still works, it just
            // stops drawing. Approve/revert stay functional because they only need the baselines.
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
