package com.github.piotrszybicki.independentintelijaiplugin.cleanup

import com.github.piotrszybicki.independentintelijaiplugin.tools.PsiTargets
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Tools | Remove Comments: strips comments out of the project -- Javadoc, line comments, or both.
 *
 * A user-driven action rather than one of the model tools -- it is here to be run by hand and its
 * effect looked at, which is why it reports what it did, does the whole thing as one undoable
 * change, and offers to count without touching anything.
 *
 * The one that throws the comments away. [MoveCommentsToDatabaseAction] is the one that keeps them.
 */
class RemoveCommentsAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        // Whatever the project view (or the editor tabs) had selected when the action was invoked.
        // Empty from a keyboard shortcut with nothing focused, which is the whole-project case.
        val selection = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList().orEmpty()

        val dialog = RemoveCommentsDialog(project, selection)
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
        val choice = dialog.choice
        // Held rather than built inline: it counts the markers it refused to touch, which is worth
        // saying and is not in the report.
        val plan = DeleteComments(choice, dialog.includeMarkers)
        val sweep = CommentSweep(project, roots, plan, dryRun)
        val what = describe(choice)
        val title = if (dryRun) "Counting $what" else "Removing $what"

        SweepRunner.launch(project, title, sweep) { report -> notifyResult(project, report, plan, what, dryRun) }
    }

    private fun describe(choice: CommentChoice): String = when (choice) {
        CommentChoice.JAVADOC -> "Javadoc comments"
        CommentChoice.EMPTY_JAVADOC -> "empty Javadoc comments"
        CommentChoice.LINE -> "line comments"
        CommentChoice.JAVADOC_AND_LINE -> "comments"
    }

    private fun notifyResult(
        project: Project,
        report: CommentSweep.Report,
        plan: DeleteComments,
        what: String,
        dryRun: Boolean,
    ) {
        if (report.comments == 0) {
            SweepRunner.notify(
                project,
                "No $what found",
                "Looked at ${report.scannedFiles} file(s).",
                NotificationType.INFORMATION,
            )
            return
        }

        if (dryRun) {
            SweepRunner.notify(
                project,
                "${report.comments} $what would be removed",
                "In ${report.filesWithComments} of ${report.scannedFiles} file(s). Nothing was changed.",
                NotificationType.INFORMATION,
            )
            return
        }

        val lines = mutableListOf("From ${report.changedFiles} of ${report.scannedFiles} file(s) scanned.")
        if (plan.markersKept > 0) {
            lines.add(
                "${plan.markersKept} <code>comment_id</code> marker(s) were left alone; the comments " +
                    "they point at are still in the database.",
            )
        }
        lines.addAll(SweepRunner.footer(report))

        SweepRunner.notify(
            project,
            "Removed ${report.applied} comment(s)",
            lines.joinToString("<br/>"),
            if (report.skipped.isEmpty()) NotificationType.INFORMATION else NotificationType.WARNING,
        )
    }
}

/** Where a sweep runs: the selection when the dialog offered it and the user took it, else the project. */
internal object SweepScope {

    fun roots(project: Project, selection: List<VirtualFile>, useSelection: Boolean): List<VirtualFile> =
        if (useSelection && selection.isNotEmpty()) {
            selection
        } else {
            listOfNotNull(PsiTargets.resolveProjectFile(project, "."))
        }
}
