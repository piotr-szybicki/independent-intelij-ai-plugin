package com.github.piotrszybicki.independentintelijaiplugin.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Puts [AgentConfiguration.FILE_NAME] in the project root the first time a project is opened.
 *
 * So that the file is something to edit rather than something to discover: the settings page can
 * only offer a dropdown of what is in it, and an empty dropdown next to a filename the user has
 * never seen is a worse first run than three working entries to change one line of.
 *
 * Writes only when there is nothing there, so it cannot undo an edit -- see
 * [AgentConfigurations.createIfMissing].
 */
class AgentConfigurationStartup : ProjectActivity {

    override suspend fun execute(project: Project) {
        AgentConfigurations.getInstance(project).createIfMissing()
    }
}
