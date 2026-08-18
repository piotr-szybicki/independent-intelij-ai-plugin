package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.intellij.ui.components.ActionLink
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent

internal class CardButton(text: String, tooltip: String, onClick: () -> Unit) {

    private var hovered = false

    private var active = true

    private val link = ActionLink(text) { if (active) onClick() }.apply {
        font = JBFont.small()
        toolTipText = tooltip
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) = setHovered(true)
            override fun mouseExited(e: MouseEvent) = setHovered(false)
        })
    }

    val component: JComponent = RoundedPanel(
        BorderLayout(),
        arc = { ChatMetrics.smallArc },
        fill = { if (hovered && active) ChatColors.cardHover else ChatColors.card },
        stroke = { ChatColors.cardBorder },
    ).apply {
        border = JBUI.Borders.empty(JBUI.scale(2), JBUI.scale(7))
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        add(link, BorderLayout.CENTER)
    }

    private fun setHovered(value: Boolean) {
        hovered = value
        component.repaint()
    }

    var isVisible: Boolean
        get() = component.isVisible
        set(value) {
            component.isVisible = value
        }

    fun settle(text: String) {
        active = false
        link.text = text
        link.foreground = ChatColors.muted
        link.toolTipText = null
        component.cursor = Cursor.getDefaultCursor()
        component.repaint()
    }
}
