package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JPanel
import javax.swing.SwingConstants

internal class ErrorRow(
    message: String,
) : ChatRow(BorderLayout(0, JBUI.scale(4))) {
    private var onRetry: (() -> Unit)? = null

    private val retry =
        CardButton("Retry", "Send this request to the model again") {
            onRetry?.invoke()
        }

    private val actions =
        JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            isVisible = false
            add(retry.component)
        }

    init {
        add(
            JBLabel(message, AllIcons.General.BalloonError, SwingConstants.LEFT).apply {
                font = JBFont.small()
                foreground = ChatColors.muted
                iconTextGap = JBUI.scale(5)
            },
            BorderLayout.CENTER,
        )
        add(actions, BorderLayout.SOUTH)
    }

    fun offerRetry(action: () -> Unit) {
        onRetry = action
        actions.isVisible = true
        revalidate()
        repaint()
    }

    fun clearRetry() {
        if (!actions.isVisible) return
        onRetry = null
        actions.isVisible = false
        revalidate()
        repaint()
    }
}
