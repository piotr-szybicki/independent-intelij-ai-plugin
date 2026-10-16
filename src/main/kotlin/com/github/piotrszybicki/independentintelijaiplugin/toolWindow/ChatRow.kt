package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import java.awt.LayoutManager
import javax.swing.JPanel

internal open class ChatRow(
    layout: LayoutManager,
) : JPanel(layout) {
    init {
        isOpaque = false
    }

    open fun applyAvailableWidth(width: Int) {}

    open fun toMarkdown(): String? = null
}
