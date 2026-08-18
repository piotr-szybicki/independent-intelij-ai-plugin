package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Dimension

internal class CappedScrollPane(
    view: Component,
    private val maxHeight: Int,
) : JBScrollPane(view) {

    override fun getPreferredSize(): Dimension {
        val preferred = super.getPreferredSize()
        return Dimension(preferred.width, minOf(preferred.height, JBUI.scale(maxHeight)))
    }

    override fun getMinimumSize(): Dimension = Dimension(0, preferredSize.height)
}
