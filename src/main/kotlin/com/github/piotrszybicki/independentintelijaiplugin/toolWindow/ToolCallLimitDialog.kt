package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

internal object ToolCallLimitDialog {
    fun confirmExtendIterations(
        project: Project,
        used: Int,
    ): Boolean {
        val answer =
            Messages.showYesNoDialog(
                project,
                "The assistant has made $used rounds of tool calls on this message and is still " +
                    "going.\n\nKeep going? It carries on from where it is, so nothing done so far " +
                    "is lost, and you will be asked again if it runs on. You can also raise Tool " +
                    "calls per message in Settings.",
                "Tool-Call Limit Reached",
                "Keep Going",
                "Stop Here",
                Messages.getQuestionIcon(),
            )
        return answer == Messages.YES
    }
}
