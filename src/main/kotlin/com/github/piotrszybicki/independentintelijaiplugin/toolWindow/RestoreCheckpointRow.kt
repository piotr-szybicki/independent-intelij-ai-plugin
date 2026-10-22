package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JPanel
import javax.swing.JSeparator

internal class RestoreCheckpointRow(
    onRestore: () -> Unit,
) : ChatRow(BorderLayout(0, JBUI.scale(4))) {
    private val restoreButton =
        CardButton(
            "Restore Checkpoint",
            "Discard this prompt and everything after it, then edit the prompt again",
            onRestore,
        ).apply {
            component.name = "chat.restoreCheckpointButton"
        }

    init {
        name = "chat.restoreCheckpoint"
        border = JBUI.Borders.empty(ChatMetrics.bubbleIndent, 0, 0, 0)
        add(
            JPanel(GridBagLayout()).apply {
                isOpaque = false
                val lineConstraints =
                    GridBagConstraints().apply {
                        fill = GridBagConstraints.HORIZONTAL
                        weightx = 1.0
                        anchor = GridBagConstraints.CENTER
                    }
                add(separator(), lineConstraints)
                add(
                    restoreButton.component,
                    GridBagConstraints().apply {
                        fill = GridBagConstraints.NONE
                        weightx = 0.0
                        anchor = GridBagConstraints.CENTER
                        insets = JBUI.insets(0, 8)
                    },
                )
                add(separator(), lineConstraints)
            },
            BorderLayout.CENTER,
        )
    }

    private fun separator() =
        JSeparator().apply {
            foreground = ChatColors.separator
            background = ChatColors.background
        }
}
