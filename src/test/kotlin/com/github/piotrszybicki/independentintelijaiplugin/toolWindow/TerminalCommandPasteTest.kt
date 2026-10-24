package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.intellij.terminal.ui.TerminalWidget
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

class TerminalCommandPasteTest {
    @Test
    fun `terminal process inspection runs off the UI thread`() {
        val terminal = terminal(running = false)
        lateinit var result: CompletableFuture<TerminalWidget?>
        SwingUtilities.invokeAndWait {
            result = TerminalCommandPaste.idleTerminal(terminal)
        }
        assertSame(terminal, result.get(10, TimeUnit.SECONDS))
    }

    @Test
    fun `busy or missing terminal requires a new session`() {
        assertNull(TerminalCommandPaste.idleTerminal(terminal(running = true)).get(10, TimeUnit.SECONDS))
        assertNull(TerminalCommandPaste.idleTerminal(null).get(10, TimeUnit.SECONDS))
    }

    private fun terminal(running: Boolean): TerminalWidget =
        Proxy.newProxyInstance(
            TerminalWidget::class.java.classLoader,
            arrayOf(TerminalWidget::class.java),
        ) { _, method, _ ->
            check(method.name == "isCommandRunning")
            assertFalse("Process inspection must not run on the EDT", SwingUtilities.isEventDispatchThread())
            running
        } as TerminalWidget
}
