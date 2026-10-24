package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.Timer

internal class CodeActionButton(
    private val originalIcon: Icon,
    private val originalTooltip: String,
    action: () -> Unit,
) : JButton(originalIcon) {
    private var feedbackStarted = 0L
    private val feedbackTimer = Timer(16) { animateFeedback() }
    private val confirmationIcon =
        object : Icon {
            override fun getIconWidth(): Int = originalIcon.iconWidth

            override fun getIconHeight(): Int = originalIcon.iconHeight

            override fun paintIcon(
                component: Component?,
                graphics: Graphics,
                x: Int,
                y: Int,
            ) {
                val canvas = graphics.create() as Graphics2D
                try {
                    canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    canvas.color = ChatColors.accent
                    canvas.stroke = BasicStroke(JBUI.scale(1.5f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    canvas.translate(x, y)
                    canvas.drawLine(iconWidth / 5, iconHeight / 2, iconWidth * 2 / 5, iconHeight * 3 / 4)
                    canvas.drawLine(iconWidth * 2 / 5, iconHeight * 3 / 4, iconWidth * 4 / 5, iconHeight / 4)
                } finally {
                    canvas.dispose()
                }
            }
        }

    init {
        toolTipText = originalTooltip
        getAccessibleContext().accessibleName = originalTooltip
        isOpaque = true
        isContentAreaFilled = true
        resetFeedback()
        addActionListener {
            action()
            feedbackStarted = System.nanoTime()
            icon = confirmationIcon
            toolTipText = if (originalTooltip == "Copy code") "Copied" else "Opening terminal…"
            animateFeedback()
            feedbackTimer.restart()
        }
    }

    override fun removeNotify() {
        feedbackTimer.stop()
        resetFeedback()
        super.removeNotify()
    }

    private fun animateFeedback() {
        val progress = ((System.nanoTime() - feedbackStarted) / 650_000_000.0).coerceIn(0.0, 1.0)
        if (progress >= 1.0) {
            feedbackTimer.stop()
            resetFeedback()
        } else {
            val strength = 1.0 - progress
            background = ChatColors.mix(ChatColors.codeBackground, ChatColors.accent, 0.22 * strength)
            border = JBUI.Borders.customLine(ChatColors.mix(ChatColors.aiBubbleBorder, ChatColors.accent, strength))
            repaint()
        }
    }

    private fun resetFeedback() {
        icon = originalIcon
        toolTipText = originalTooltip
        background = ChatColors.codeBackground
        border = JBUI.Borders.customLine(ChatColors.aiBubbleBorder)
        repaint()
    }
}
