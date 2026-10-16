package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

object ChangeDialogs {
    fun confirmRevert(
        project: Project,
        paths: List<Any>,
    ): Boolean {
        val fileList = paths.joinToString("\n") { "  $it" }
        val confirmed =
            Messages.showYesNoDialog(
                project,
                "Restore ${paths.size} file(s) to their state before this session?\n\n$fileList\n\n" +
                    "Any edits you made to these files yourself will be discarded too.",
                "Revert AI Changes",
                "Revert",
                "Cancel",
                Messages.getWarningIcon(),
            )
        return confirmed == Messages.YES
    }

    fun showRevertFailure(
        project: Project,
        failed: List<Any>,
    ) {
        Messages.showWarningDialog(
            project,
            "These files could not be restored:\n\n" + failed.joinToString("\n") { "  $it" },
            "Revert Incomplete",
        )
    }
}
