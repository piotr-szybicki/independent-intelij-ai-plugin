package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import javax.swing.JButton
import javax.swing.SwingUtilities

class ShellCommandControlSizeTest {
    @Test
    fun `button width stays compact across code lengths and panel sizes`() {
        SwingUtilities.invokeAndWait {
            var measuredWidth: Int? = null
            for (code in listOf("git status", "x".repeat(300))) {
                for (width in listOf(240, 600, 1000)) {
                    val pane = HtmlTextPane(allowHorizontalOverflow = true)
                    pane.setHtml(ShellCommandBlocks("```bash\n$code\n```").html)
                    pane.applyWidth(width)
                    pane.size = pane.preferredSize
                    val controls = ShellCodeControls(pane, listOf(code), {}, {})
                    pane.layoutControls = controls::layout
                    pane.doLayout()
                    val buttons = pane.components.filterIsInstance<JButton>().map { it.bounds }
                    assertEquals(2, buttons.size)
                    val buttonWidth = buttons.first().width
                    assertEquals("Both buttons must have the same width", buttonWidth, buttons.last().width)
                    assertEquals(
                        "Both buttons must have the same height",
                        buttons.first().height,
                        buttons.last().height,
                    )
                    assertTrue("Buttons need a gap", buttons.last().x - buttons.first().maxX >= 4)
                    assertTrue("Button stretched to $buttonWidth at panel width $width", buttonWidth in 1..32)
                    measuredWidth?.let { assertEquals("Button width must stay fixed", it, buttonWidth) }
                    measuredWidth = buttonWidth
                    assertTrue("Buttons must overlap the top border", buttons.first().y > 0)
                    val image = BufferedImage(pane.width, pane.height, BufferedImage.TYPE_INT_RGB)
                    pane.components.forEach { it.isVisible = false }
                    val graphics = image.createGraphics()
                    graphics.color = ChatColors.aiBubble
                    graphics.fillRect(0, 0, image.width, image.height)
                    pane.paint(graphics)
                    graphics.dispose()
                    val borderY =
                        (0 until image.height).first {
                            image.getRGB(image.width / 2, it) == ChatColors.codeBackground.rgb
                        }
                    assertEquals(
                        "Button center must align with painted border",
                        borderY,
                        buttons.first().centerY.toInt(),
                    )
                }
            }
        }
    }
}
