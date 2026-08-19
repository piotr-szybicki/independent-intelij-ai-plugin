package com.github.piotrszybicki.independentintelijaiplugin.cleanup

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project

class RestoreCommentsAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selection = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList().orEmpty()

        val dialog = CommentDatabaseDialog(project, selection, CommentDatabaseDialog.Mode.RESTORE)
        if (!dialog.showAndGet()) return

        val roots = SweepScope.roots(project, selection, dialog.useSelection)
        if (roots.isEmpty()) {
            SweepRunner.notify(
                project,
                "Nowhere to look",
                "The project has no directory on disk.",
                NotificationType.WARNING,
            )
            return
        }

        val dryRun = dialog.dryRun
        val plan = RestoreMarkers(project)
        val sweep = CommentSweep(project, roots, plan, dryRun)
        val title = if (dryRun) "Counting comment markers" else "Restoring comments from the database"

        SweepRunner.launch(project, title, sweep) { report -> notifyResult(project, report, plan, dryRun) }
    }

    private fun notifyResult(
        project: Project,
        report: CommentSweep.Report,
        plan: RestoreMarkers,
        dryRun: Boolean,
    ) {
        if (report.comments == 0) {
            SweepRunner.notify(
                project,
                "No comment markers found",
                "Looked at ${report.scannedFiles} file(s) for <code>// comment_id: N</code> lines.",
                NotificationType.INFORMATION,
            )
            return
        }

        if (dryRun) {
            SweepRunner.notify(
                project,
                "${report.comments} marker(s) would be restored",
                "In ${report.filesWithComments} of ${report.scannedFiles} file(s). Nothing was changed.",
                NotificationType.INFORMATION,
            )
            return
        }

        val lines = mutableListOf("Into ${report.changedFiles} of ${report.scannedFiles} file(s) scanned.")
        if (plan.missing > 0) {
            lines.add("${plan.missing} marker(s) had no row in the table and were left as they are.")
        }
        lines.addAll(SweepRunner.footer(report))

        SweepRunner.notify(
            project,
            "Restored ${report.applied} comment(s)",
            lines.joinToString("<br/>"),
            if (report.skipped.isEmpty() && plan.missing == 0) NotificationType.INFORMATION else NotificationType.WARNING,
        )
    }
}
