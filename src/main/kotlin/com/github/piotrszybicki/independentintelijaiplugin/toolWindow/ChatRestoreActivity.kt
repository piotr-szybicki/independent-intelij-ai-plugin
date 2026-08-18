package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Lets a chat panel that was rebuilt during frame init reopen the conversation it was last left on.
 *
 * A tool window that was open when the IDE closed is restored before the project is, and reading the
 * chat back from disk -- let alone instantiating the service that does it -- is not work for frame
 * init. The panel therefore holds it back, and this activity is the signal that the wait is over.
 *
 * A window opened by hand after startup never gets here: it restores itself on construction, because
 * by then the project is open. See `ChatToolWindowFactory.ChatPanel.restoreLastChat`.
 */
class ChatRestoreActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        ChatToolWindowFactory.projectOpened(project)
    }
}
