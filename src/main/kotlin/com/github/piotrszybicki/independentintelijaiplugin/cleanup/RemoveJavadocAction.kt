package com.github.piotrszybicki.independentintelijaiplugin.cleanup

import com.github.piotrszybicki.independentintelijaiplugin.tools.PsiTargets
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Tools | Remove Javadoc Comments: strips the documentation comments out of the project.
 *
 * A user-driven action rather than one of the model tools -- it is here to be run by hand and its
 * effect looked at, which is why it reports what it did, does the whole thing as one undoable
 * change, and offers to count without touching anything.
 */
class RemoveJavadocAction : AnAction() {

    private companion object {
        const val NOTIFICATION_GROUP = "AICodingAgent.Cleanup"
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        // Whatever the project view (or the editor tabs) had selected when the action was invoked.
        // Empty from a keyboard shortcut with nothing focused, which is the whole-project case.
        val selection = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList().orEmpty()

        val dialog = RemoveJavadocDialog(project, selection)
        if (!dialog.showAndGet()) return

        val roots = if (dialog.useSelection && selection.isNotEmpty()) {
            selection
        } else {
            listOfNotNull(projectRoot(project))
        }
        if (roots.isEmpty()) {
            notify(project, "Nowhere to look", "The project has no directory on disk.", NotificationType.WARNING)
            return
        }

        val dryRun = dialog.dryRun
        val sweep = JavadocSweep(project, roots, dialog.onlyBlank, dryRun)
        val title = if (dryRun) "Counting Javadoc comments" else "Removing Javadoc comments"

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
            private var report: JavadocSweep.Report? = null

            override fun run(indicator: ProgressIndicator) {
                report = sweep.run(indicator)
            }

            override fun onSuccess() {
                report?.let { notifyResult(project, it, dryRun) }
            }
        })
    }

    private fun projectRoot(project: Project): VirtualFile? = PsiTargets.resolveProjectFile(project, ".")

    private fun notifyResult(project: Project, report: JavadocSweep.Report, dryRun: Boolean) {
        if (report.comments == 0) {
            notify(
                project,
                "No Javadoc comments found",
                "Looked at ${report.scannedFiles} file(s).",
                NotificationType.INFORMATION,
            )
            return
        }

        if (dryRun) {
            notify(
                project,
                "${report.comments} Javadoc comment(s) would be removed",
                "In ${report.filesWithComments} of ${report.scannedFiles} file(s). Nothing was changed.",
                NotificationType.INFORMATION,
            )
            return
        }

        val lines = mutableListOf("From ${report.changedFiles} of ${report.scannedFiles} file(s) scanned.")
        if (report.skipped.isNotEmpty()) {
            // Read-only, or changed between the scan and the write -- either way the file still has
            // its comments, and saying which ones is the difference between a warning and a mystery.
            lines.add("Left alone (read-only, or edited while the sweep ran): ${report.skipped.joinToString(", ")}")
        }
        lines.add("Undo in any edited file takes the whole sweep back.")

        notify(
            project,
            "Removed ${report.removed} Javadoc comment(s)",
            lines.joinToString("<br/>"),
            if (report.skipped.isEmpty()) NotificationType.INFORMATION else NotificationType.WARNING,
        )
    }

    private fun notify(project: Project, title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(title, content, type)
            .notify(project)
    }
}
