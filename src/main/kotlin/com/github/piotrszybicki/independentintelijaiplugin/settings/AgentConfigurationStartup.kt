package com.github.piotrszybicki.independentintelijaiplugin.settings

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.text.StringUtil

/**
 * Puts [AgentConfiguration.FILE_NAME] in the project root the first time a project is opened.
 *
 * So that the file is something to edit rather than something to discover: the settings page can
 * only offer a dropdown of what is in it, and an empty dropdown next to a filename the user has
 * never seen is a worse first run than three working entries to change one line of.
 *
 * Writes only when there is nothing there, so it cannot undo an edit -- see
 * [AgentConfigurations.createIfMissing].
 *
 * Writes nothing at all when [AgentConfiguration.PATH_ENV_VAR] is set, and reports it here when that
 * variable names a file that is not there. That is the one state the plugin refuses to run in rather
 * than working around, and it is reported at startup because the alternative is finding out from a
 * tool window that will not open -- see [AgentConfigurations.unavailableReason].
 */
class AgentConfigurationStartup : ProjectActivity {

    override suspend fun execute(project: Project) {
        val configurations = AgentConfigurations.getInstance(project)
        configurations.unavailableReason?.let { reason ->
            LOG.warn("AICodingAgent is not starting: $reason")
            // Sticky, because it is the whole reason the tool window says what it says, and a
            // balloon that fades is a balloon that is missed while the IDE is still opening files.
            NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification(
                    "AICodingAgent is not available",
                    StringUtil.escapeXmlEntities(reason) + "<br/><br/>Create the file at that path, or unset " +
                        "<code>${AgentConfiguration.PATH_ENV_VAR}</code> and restart the IDE to use " +
                        "<code>${AgentConfiguration.FILE_NAME}</code> in the project root.",
                    NotificationType.ERROR,
                )
                .notify(project)
            return
        }
        configurations.createIfMissing()
    }

    private companion object {
        private const val NOTIFICATION_GROUP = "AICodingAgent"
        private val LOG = Logger.getInstance(AgentConfigurationStartup::class.java)
    }
}
