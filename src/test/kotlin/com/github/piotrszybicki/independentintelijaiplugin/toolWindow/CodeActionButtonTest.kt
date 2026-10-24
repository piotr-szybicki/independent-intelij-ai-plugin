package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.intellij.icons.AllIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

class CodeActionButtonTest {
    @Test
    fun `click feedback resets without moving the button or repeating its action`() {
        val restored = CountDownLatch(1)
        var calls = 0
        lateinit var button: CodeActionButton
        val original = AllIcons.Actions.Copy
        SwingUtilities.invokeAndWait {
            button = CodeActionButton(original, "Copy code") { calls++ }
            button.setBounds(30, 40, 24, 24)
            button.addPropertyChangeListener("icon") {
                if (it.newValue === original) restored.countDown()
            }
            button.doClick(0)
            assertNotSame(original, button.icon)
            assertEquals("Copied", button.toolTipText)
        }
        assertTrue("Feedback must finish", restored.await(5, TimeUnit.SECONDS))
        SwingUtilities.invokeAndWait {
            assertSame(original, button.icon)
            assertEquals("Copy code", button.toolTipText)
            assertEquals(java.awt.Rectangle(30, 40, 24, 24), button.bounds)
            assertEquals(1, calls)
        }
    }
}
