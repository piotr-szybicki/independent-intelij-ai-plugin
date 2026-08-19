package com.github.piotrszybicki.independentintelijaiplugin.settings

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.text.StringUtil

class AgentConfigurationStartup : ProjectActivity {

    override suspend fun execute(project: Project) {
        val configurations = AgentConfigurations.getInstance(project)
        configurations.unavailableReason?.let { reason ->
            LOG.warn("AICodingAgent is not starting: $reason")
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
