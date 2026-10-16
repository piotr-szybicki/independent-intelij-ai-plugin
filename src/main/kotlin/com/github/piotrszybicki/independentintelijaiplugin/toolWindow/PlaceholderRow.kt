package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import javax.swing.SwingConstants

internal class PlaceholderRow : ChatRow(VerticalLayout(JBUI.scale(4), VerticalLayout.FILL)) {
    init {
        border = JBUI.Borders.empty(28, 8, 0, 8)
        add(
            JBLabel("Ask AI about this project", SwingConstants.CENTER).apply {
                font = JBFont.label().asBold()
                foreground = ChatColors.foreground
            },
        )
        add(
            JBLabel(
                "Attach a selection for context — Enter sends, Shift+Enter adds a line",
                SwingConstants.CENTER,
            ).apply {
                font = JBFont.small()
                foreground = ChatColors.muted
            },
        )
        add(
            JBLabel("Type @ to hand the work over to an agent", SwingConstants.CENTER).apply {
                font = JBFont.small()
                foreground = ChatColors.muted
            },
        )
    }
}
