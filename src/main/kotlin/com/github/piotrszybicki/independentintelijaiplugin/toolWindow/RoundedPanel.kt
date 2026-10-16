package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LayoutManager
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.JPanel

internal open class RoundedPanel(
    layout: LayoutManager? = null,
    private val arc: () -> Int = { ChatMetrics.arc },
    private val fill: () -> Color? = { null },
    private val stroke: () -> Color? = { null },
    private val strokeWidth: () -> Float = { 1f },
) : JPanel(layout) {
    init {
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        val graphics = g.create() as Graphics2D
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arcSize = arc()
            fill()?.let { color ->
                graphics.color = color
                graphics.fillRoundRect(0, 0, width, height, arcSize, arcSize)
            }
            stroke()?.let { color ->
                graphics.color = color
                val pen = strokeWidth()
                graphics.stroke = BasicStroke(pen)
                val inset = pen / 2f
                graphics.draw(
                    RoundRectangle2D.Float(
                        inset,
                        inset,
                        width - pen,
                        height - pen,
                        arcSize.toFloat(),
                        arcSize.toFloat(),
                    ),
                )
            }
        } finally {
            graphics.dispose()
        }
    }
}
