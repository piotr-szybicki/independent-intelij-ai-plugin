package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.intellij.util.ui.JBUI

internal object ChatMetrics {
    val rowGap: Int get() = JBUI.scale(10)
    val bubblePadding: Int get() = JBUI.scale(9)
    val bubbleIndent: Int get() = JBUI.scale(28)
    val arc: Int get() = JBUI.scale(12)
    val smallArc: Int get() = JBUI.scale(8)
}
