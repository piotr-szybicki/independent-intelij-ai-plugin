package com.github.piotrszybicki.independentintelijaiplugin.cleanup

import com.github.piotrszybicki.independentintelijaiplugin.logging.CommentDatabase
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project

/**
 * Tools | Move Javadoc Comments to Database: the documentation comes out of the code and goes into
 * the `code_comments` table, leaving `// comment_id: N` where each one stood.
 *
 * The counterpart of [RemoveJavadocAction] rather than a variant of it: the same comments are found
 * the same way, but the text is kept, and the code keeps a way back to it. [RestoreCommentsAction]
 * is that way back.
 */
class MoveCommentsToDatabaseAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selection = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList().orEmpty()

        val dialog = CommentDatabaseDialog(project, selection, CommentDatabaseDialog.Mode.STORE)
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
        // Held rather than built inline: it counts the comments it could not replace with a line
        // comment, which is the one thing about a store worth knowing and is not in the report.
        val plan = StoreJavadoc(project)
        val sweep = CommentSweep(project, roots, plan, dryRun)
        val title = if (dryRun) "Counting Javadoc comments" else "Moving Javadoc comments to the database"

        SweepRunner.launch(project, title, sweep) { report -> notifyResult(project, report, plan, dryRun) }
    }

    private fun notifyResult(
        project: Project,
        report: CommentSweep.Report,
        plan: StoreJavadoc,
        dryRun: Boolean,
    ) {
        if (report.comments == 0) {
            SweepRunner.notify(
                project,
                "No Javadoc comments found",
                "Looked at ${report.scannedFiles} file(s).",
                NotificationType.INFORMATION,
            )
            return
        }

        if (dryRun) {
            SweepRunner.notify(
                project,
                "${report.comments} Javadoc comment(s) would be moved",
                "In ${report.filesWithComments} of ${report.scannedFiles} file(s). Nothing was " +
                    "changed and nothing was written to the database.",
                NotificationType.INFORMATION,
            )
            return
        }

        val lines = mutableListOf(
            "Stored in <code>${CommentDatabase.TABLE}</code>, and replaced in " +
                "${report.changedFiles} of ${report.scannedFiles} file(s) with a comment_id marker.",
        )
        if (plan.inline > 0) {
            lines.add(
                "${plan.inline} comment(s) sat on a line with code and were left alone: a " +
                    "<code>//</code> marker there would comment out the rest of the line.",
            )
        }
        lines.addAll(SweepRunner.footer(report))
        // Worth saying every time rather than once in the dialog: the rows outlive the Undo, so
        // taking the edit back does not take the storing back.
        lines.add("Undo restores the files; the rows stay in the table.")

        SweepRunner.notify(
            project,
            "Moved ${report.applied} comment(s) into the database",
            lines.joinToString("<br/>"),
            if (report.skipped.isEmpty()) NotificationType.INFORMATION else NotificationType.WARNING,
        )
    }
}
