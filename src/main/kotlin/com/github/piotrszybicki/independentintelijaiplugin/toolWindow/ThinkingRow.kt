package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.InplaceButton
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.SwingConstants

internal class ThinkingRow(
    onCancel: () -> Unit,
) : ChatRow(BorderLayout(JBUI.scale(6), 0)) {
    private companion object {
        const val ENABLED_TOOLTIP = "Stop — cancel what the AI is doing"
        const val DISABLED_TOOLTIP = "Cannot be stopped from here — this is running in the terminal"
    }

    private var cancellable = true
    private val label =
        JBLabel("AI is working", AnimatedIcon.Default.INSTANCE, SwingConstants.LEFT).apply {
            font = JBFont.small()
            foreground = ChatColors.muted
            iconTextGap = JBUI.scale(6)
        }

    private val stop =
        InplaceButton(ENABLED_TOOLTIP, AllIcons.Actions.Suspend) {
            if (cancellable) onCancel()
        }

    init {
        add(stop, BorderLayout.WEST)
        add(label, BorderLayout.CENTER)
    }

    fun setCancellable(value: Boolean) {
        if (cancellable == value) return
        cancellable = value
        stop.icon = if (value) AllIcons.Actions.Suspend else IconLoader.getDisabledIcon(AllIcons.Actions.Suspend)
        stop.setActive(value)
        stop.toolTipText = if (value) ENABLED_TOOLTIP else DISABLED_TOOLTIP
        label.text = if (value) "AI is working" else "AI is waiting for the terminal"
        stop.repaint()
    }
}
