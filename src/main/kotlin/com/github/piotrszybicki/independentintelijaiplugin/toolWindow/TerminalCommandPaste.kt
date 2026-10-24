package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.github.piotrszybicki.independentintelijaiplugin.tools.ChatWorkingDirectory
import com.intellij.ide.DataManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.terminal.JBTerminalWidget
import com.intellij.terminal.frontend.view.TerminalView
import com.intellij.terminal.ui.TerminalWidget
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.util.concurrent.CompletableFuture

internal object TerminalCommandPaste {
    fun paste(
        project: Project,
        command: String,
    ) {
        if (project.isDisposed) return
        val manager = TerminalToolWindowManager.getInstance(project)
        val selected =
            manager.toolWindow
                ?.contentManager
                ?.selectedContent
                ?.let(TerminalToolWindowManager::findWidgetByContent)
        idleTerminal(selected).thenAccept { idle ->
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) {
                    val terminal =
                        idle
                            ?: manager.createNewSession(
                                ChatWorkingDirectory.path(project),
                                "AI commands",
                                null,
                                true,
                                true,
                            )
                    pasteInto(manager, terminal, command)
                }
            }
        }
    }

    internal fun idleTerminal(selected: TerminalWidget?): CompletableFuture<TerminalWidget?> =
        CompletableFuture.supplyAsync(
            { selected?.takeUnless { it.isCommandRunning() } },
            AppExecutorUtil.getAppExecutorService(),
        )

    private fun pasteInto(
        manager: TerminalToolWindowManager,
        terminal: TerminalWidget,
        command: String,
    ) {
        manager.toolWindow?.activate {
            whenInitialized(terminal) {
                terminal.requestFocus()
                val context = DataManager.getInstance().getDataContext(terminal.preferredFocusableComponent)
                val view = TerminalView.DATA_KEY.getData(context)
                TerminalCommandInput.send(terminal, view, command)
            }
        }
    }

    internal fun whenInitialized(
        terminal: TerminalWidget,
        dispatch: (() -> Unit) -> Unit = { ApplicationManager.getApplication().invokeLater(it) },
        paste: () -> Unit,
    ) {
        if (JBTerminalWidget.asJediTermWidget(terminal) != null) {
            terminal.ttyConnectorAccessor.executeWithTtyConnector { dispatch(paste) }
        } else {
            terminal.getTerminalSizeInitializedFuture().thenRun {
                dispatch(paste)
            }
        }
    }
}
