package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.intellij.build.DefaultBuildDescriptor
import com.intellij.build.FilePosition
import com.intellij.build.events.MessageEvent
import com.intellij.build.events.impl.FileMessageEventImpl
import com.intellij.build.events.impl.MessageEventImpl
import com.intellij.build.events.impl.StartBuildEventImpl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RebuildDiagnosticsTest {
    @Test
    fun `collects only matching error events with file locations and details`() {
        val id = Any()
        val diagnostics = RebuildDiagnostics(id)
        val error =
            FileMessageEventImpl(
                id,
                MessageEvent.Kind.ERROR,
                "compiler",
                "Unresolved reference",
                "Unresolved reference: missingName",
                FilePosition(File("Example.kt"), 6, 2),
            )
        diagnostics.accept(id, error)
        diagnostics.accept(id, error)
        diagnostics.accept(Any(), MessageEventImpl(id, MessageEvent.Kind.ERROR, "compiler", "unrelated", "unrelated"))
        diagnostics.accept(id, MessageEventImpl(id, MessageEvent.Kind.WARNING, "compiler", "warning", "warning"))

        val result = diagnostics.failureMessage()
        assertTrue(result.contains("Example.kt:7:3"))
        assertTrue(result.contains("Unresolved reference: missingName"))
        assertFalse(result.contains("unrelated"))
        assertFalse(result.contains("warning"))
        assertTrue(result.indexOf("Example.kt") == result.lastIndexOf("Example.kt"))
    }

    @Test
    fun `reports when no diagnostics are available and when output is limited`() {
        val id = Any()
        val diagnostics = RebuildDiagnostics(id)
        assertTrue(diagnostics.failureMessage().contains("did not provide"))
        repeat(51) { index ->
            diagnostics.accept(id, MessageEventImpl(id, MessageEvent.Kind.ERROR, "compiler", "error $index", ""))
        }
        assertTrue(diagnostics.failureMessage().contains("1 additional error(s) omitted"))
    }

    @Test
    fun `labels uncorrelated errors and excludes them when several builds start`() {
        val diagnostics = RebuildDiagnostics(Any())
        val first = Any()
        diagnostics.accept(first, StartBuildEventImpl(DefaultBuildDescriptor(first, "Build", ".", 0), "start"))
        diagnostics.accept(first, MessageEventImpl(first, MessageEvent.Kind.ERROR, "compiler", "error one", ""))
        assertTrue(diagnostics.failureMessage().contains("did not identify it as this rebuild"))
        assertTrue(diagnostics.failureMessage().contains("error one"))

        val second = Any()
        diagnostics.accept(second, StartBuildEventImpl(DefaultBuildDescriptor(second, "Build", ".", 0), "start"))
        assertFalse(diagnostics.failureMessage().contains("error one"))
        assertTrue(diagnostics.failureMessage().contains("did not provide"))
    }
}
