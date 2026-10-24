package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.intellij.terminal.JBTerminalSystemSettingsProviderBase
import com.intellij.terminal.JBTerminalWidget
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jediterm.terminal.TtyConnector
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class TerminalClassicBridgeTest : BasePlatformTestCase() {
    fun testClassicBridgeWaitsForConnectorAndReceivesCommand() {
        val widget = JBTerminalWidget(project, JBTerminalSystemSettingsProviderBase(), testRootDisposable)
        val bridge = widget.asNewWidget()
        val written = CompletableFuture<String>()
        val connector =
            Proxy.newProxyInstance(
                TtyConnector::class.java.classLoader,
                arrayOf(TtyConnector::class.java),
            ) { _, method, args ->
                check(method.name == "write")
                written.complete(args.single() as String)
                null
            } as TtyConnector
        var initialized = false
        TerminalCommandPaste.whenInitialized(bridge, dispatch = { it() }) {
            initialized = true
            TerminalCommandInput.send(bridge, null, "git status")
        }
        assertFalse(initialized)
        assertFalse(written.isDone)

        bridge.ttyConnectorAccessor.ttyConnector = connector

        assertTrue(initialized)
        assertEquals("\u001b[200~git status\u001b[201~", written.get(10, TimeUnit.SECONDS))
    }
}
