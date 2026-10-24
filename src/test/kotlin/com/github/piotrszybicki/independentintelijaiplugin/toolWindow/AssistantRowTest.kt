package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.JButton
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities

class AssistantRowTest {
    @Test
    fun `copy control copies code without pasting to the terminal`() {
        SwingUtilities.invokeAndWait {
            var copied: String? = null
            var pasted = false
            val row =
                AssistantRow("```bash\necho '<hello>'\ngit status\n```", onCopyCode = { copied = it }) {
                    pasted = true
                }
            val scroll = row.components.single() as JScrollPane
            val body = scroll.viewport.view as HtmlTextPane
            val copy = body.components.filterIsInstance<JButton>().first { it.toolTipText == "Copy code" }
            copy.doClick()
            assertEquals("echo '<hello>'\ngit status", copied)
            assertEquals(false, pasted)
            assertEquals("Copied", copy.toolTipText)
        }
    }

    @Test
    fun `shell command control passes the code to the terminal callback`() {
        SwingUtilities.invokeAndWait {
            var pasted: String? = null
            val row = AssistantRow("```bash\ngit status\n```") { pasted = it }
            val scroll = row.components.single() as JScrollPane
            val body = scroll.viewport.view as HtmlTextPane
            body.components
                .filterIsInstance<JButton>()
                .first { it.toolTipText == "Paste to terminal" }
                .doClick()
            assertEquals("git status", pasted)
        }
    }

    @Test
    fun `wide code remains reachable and scrollbar disappears after widening`() {
        SwingUtilities.invokeAndWait {
            val code = "x".repeat(300)
            val row = AssistantRow("```\n$code\n```")
            val scroll = row.components.single() as JScrollPane
            row.applyAvailableWidth(240)
            row.setSize(240, row.preferredSize.height)
            row.doLayout()
            scroll.doLayout()
            scroll.viewport.doLayout()

            assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS, scroll.horizontalScrollBarPolicy)
            assertTrue(scroll.horizontalScrollBar.isVisible)
            assertTrue(scroll.viewport.viewSize.width > scroll.viewport.extentSize.width)
            scroll.horizontalScrollBar.value = scroll.horizontalScrollBar.maximum
            assertTrue(scroll.viewport.viewPosition.x > 0)

            row.applyAvailableWidth(10000)
            assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER, scroll.horizontalScrollBarPolicy)
        }
    }

    @Test
    fun `ordinary prose wraps without horizontal scrolling`() {
        SwingUtilities.invokeAndWait {
            val row = AssistantRow("Some ordinary answer text. ".repeat(60))
            val scroll = row.components.single() as JScrollPane
            row.applyAvailableWidth(240)
            val narrowHeight = row.preferredSize.height
            assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER, scroll.horizontalScrollBarPolicy)

            row.applyAvailableWidth(800)
            assertTrue(narrowHeight > row.preferredSize.height)
        }
    }
}
