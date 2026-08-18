package com.github.piotrszybicki.independentintelijaiplugin.cleanup

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project

/**
 * Runs a [CommentSweep] in the background and says what happened.
 *
 * Shared by the three comment actions, which differ only in what they tell the user afterwards. A
 * sweep that throws -- a database that cannot be reached, most likely -- reports the message rather
 * than leaving it in `idea.log`: the user pressed a menu item and is owed an answer either way.
 */
internal object SweepRunner {

    private const val NOTIFICATION_GROUP = "AICodingAgent.Cleanup"

    fun launch(
        project: Project,
        title: String,
        sweep: CommentSweep,
        report: (CommentSweep.Report) -> Unit,
    ) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
            private var result: CommentSweep.Report? = null
            private var failure: String? = null

            override fun run(indicator: ProgressIndicator) {
                try {
                    result = sweep.run(indicator)
                } catch (e: ProcessCanceledException) {
                    // The user pressed Stop. Nothing to report, and rethrowing is what tells the
                    // platform to run onCancel rather than onSuccess.
                    throw e
                } catch (e: Exception) {
                    failure = e.message ?: e.toString()
                }
            }

            override fun onSuccess() {
                val stopped = failure
                if (stopped != null) {
                    notify(project, "$title stopped", stopped, NotificationType.ERROR)
                    return
                }
                result?.let(report)
            }
        })
    }

    fun notify(project: Project, title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(title, content, type)
            .notify(project)
    }

    /**
     * The tail every summary ends with: which files were left alone, and that Undo takes it back.
     *
     * Read-only, or changed between the scan and the write -- either way the file still holds what
     * it held, and naming it is the difference between a warning and a mystery.
     */
    fun footer(report: CommentSweep.Report): List<String> {
        val lines = mutableListOf<String>()
        if (report.skipped.isNotEmpty()) {
            lines.add("Left alone (read-only, or edited while the sweep ran): ${report.skipped.joinToString(", ")}")
        }
        lines.add("Undo in any edited file takes the whole sweep back.")
        return lines
    }
}
