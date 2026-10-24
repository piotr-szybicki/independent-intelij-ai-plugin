package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.intellij.terminal.frontend.view.TerminalView
import com.intellij.terminal.ui.TerminalWidget
import com.intellij.terminal.ui.TtyConnectorAccessor
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.TtyConnector
import org.jetbrains.plugins.terminal.view.TerminalSendTextBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

class TerminalCommandInputTest {
    @Test
    fun `new terminal receives text only after initialization`() {
        val ready = CompletableFuture<TermSize>()
        val terminal =
            proxy<TerminalWidget> { method, _ ->
                check(method == "getTerminalSizeInitializedFuture")
                ready
            }
        var pasted = false
        TerminalCommandPaste.whenInitialized(terminal, dispatch = { it() }) { pasted = true }
        assertFalse(pasted)
        ready.complete(TermSize(80, 24))
        assertTrue(pasted)
    }

    @Test
    fun `modern terminal receives exact text in paste mode without executing`() {
        val calls = mutableListOf<String>()
        val command = "echo hello\necho world"
        lateinit var builder: TerminalSendTextBuilder
        builder =
            proxy<TerminalSendTextBuilder> { method, args ->
                calls += method
                if (method == "send") {
                    assertEquals(command, args?.single())
                    null
                } else {
                    builder
                }
            }
        val view =
            proxy<TerminalView> { method, _ ->
                check(method == "createSendTextBuilder")
                builder
            }
        val terminal = proxy<TerminalWidget> { _, _ -> error("Modern paste must not use the legacy connector") }
        TerminalCommandInput.send(terminal, view, command)
        assertEquals(listOf("useBracketedPasteMode", "send"), calls)
    }

    @Test
    fun `classic terminal queues text until its connector is ready`() {
        val accessor = TtyConnectorAccessor()
        val terminal =
            proxy<TerminalWidget> { method, _ ->
                check(method == "getTtyConnectorAccessor")
                accessor
            }
        val written = CompletableFuture<String>()
        val connector =
            proxy<TtyConnector> { method, args ->
                check(method == "write")
                assertFalse(SwingUtilities.isEventDispatchThread())
                written.complete(args?.single() as String)
                null
            }
        TerminalCommandInput.send(terminal, null, "git status")
        assertFalse(written.isDone)
        accessor.ttyConnector = connector
        assertEquals("\u001b[200~git status\u001b[201~", written.get(10, TimeUnit.SECONDS))
    }

    private inline fun <reified T> proxy(crossinline call: (String, Array<out Any?>?) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            call(method.name, args)
        } as T
}
