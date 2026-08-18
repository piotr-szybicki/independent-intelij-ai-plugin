package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.github.piotrszybicki.independentintelijaiplugin.settings.AICodingAgentSettingsConfigurable
import com.github.piotrszybicki.independentintelijaiplugin.settings.AgentConfiguration

internal object ChatDialogs {

    fun promptForMissingApiKey(project: Project, configuration: AgentConfiguration) {
        val message = configuration.tokenEnvVar?.let { variable ->
            "The \"${configuration.name}\" configuration reads its token from the $variable " +
                    "environment variable, which is empty or undefined. Set it and restart the IDE, " +
                    "which only sees the variables it was launched with."
        } ?: ("The \"${configuration.name}\" configuration in ${AgentConfiguration.FILE_NAME} has " +
                "no token. Put one there, or write \$NAME to read it from an environment variable.")

        val openSettings = Messages.showYesNoDialog(
            project,
            message,
            "API Key Missing",
            "Open Settings",
            "Cancel",
            Messages.getWarningIcon(),
        )
        if (openSettings == Messages.YES) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, AICodingAgentSettingsConfigurable::class.java)
        }
    }

    fun confirmContinue(project: Project, limit: Int, suggested: Int): Int? {
        val answer = Messages.showYesNoDialog(
            project,
            "The reply was cut off at the $limit-token output limit.\n\nContinue the reply where " +
                "it stopped? The rest of this chat gets $suggested tokens a reply, so it is less " +
                "likely to happen again. This sends another request, so it costs an extra turn.",
            "Response Cut Off",
            "Continue",
            "Stop Here",
            Messages.getQuestionIcon(),
        )
        return if (answer == Messages.YES) suggested else null
    }

    fun askForMaxTokens(project: Project, limit: Int): Int? {
        val typed = Messages.showInputDialog(
            project,
            "The reply was cut off at the $limit-token output limit, which is as far as this chat " +
                "raises it on its own.\n\nSet the limit for the rest of this chat and continue, or " +
                "cancel to keep the answer as it is. Replies much past this size risk timing out " +
                "before they arrive.",
            "Response Cut Off",
            Messages.getQuestionIcon(),
            limit.toString(),
            object : InputValidator {
                override fun checkInput(inputString: String): Boolean =
                    inputString.trim().toIntOrNull()?.let { it > 0 } == true

                override fun canClose(inputString: String): Boolean = checkInput(inputString)
            },
        )
        return typed?.trim()?.toIntOrNull()?.takeIf { it > 0 }
    }

    fun confirmExtendIterations(project: Project, used: Int): Boolean {
        val answer = Messages.showYesNoDialog(
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

    fun confirmRevert(project: Project, paths: List<Any>): Boolean {
        val fileList = paths.joinToString("\n") { "  $it" }
        val confirmed = Messages.showYesNoDialog(
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

    fun showRevertFailure(project: Project, failed: List<Any>) {
        Messages.showWarningDialog(
            project,
            "These files could not be restored:\n\n" + failed.joinToString("\n") { "  $it" },
            "Revert Incomplete",
        )
    }
}
