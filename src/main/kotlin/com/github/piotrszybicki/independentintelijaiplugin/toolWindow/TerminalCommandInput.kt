package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.intellij.terminal.frontend.view.TerminalView
import com.intellij.terminal.ui.TerminalWidget
import com.intellij.util.concurrency.AppExecutorUtil

internal object TerminalCommandInput {
    fun send(
        terminal: TerminalWidget,
        view: TerminalView?,
        command: String,
    ) {
        if (view != null) {
            view.createSendTextBuilder().useBracketedPasteMode().send(command)
        } else {
            terminal.ttyConnectorAccessor.executeWithTtyConnector { connector ->
                AppExecutorUtil.getAppExecutorService().execute {
                    connector.write("\u001b[200~$command\u001b[201~")
                }
            }
        }
    }
}
