package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class ChatRestoreActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        ChatToolWindowFactory.projectOpened(project)
    }
}
