package com.github.piotrszybicki.independentintelijaiplugin.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleDigestTest {

    private val projectPath = "C:/Users/piotr/Documents/intellij-platform-plugin-template"

    @Test
    fun `keeps unrelated lines verbatim`() {
        val text = "> Task :compileKotlin\nBUILD SUCCESSFUL in 12s\n3 actionable tasks: 3 executed\n"
        assertEquals(text.trim(), ConsoleDigest.of(text))
    }

    @Test
    fun `keeps a pair of repeats verbatim`() {
        val text = "warning: deprecated\nwarning: deprecated\n"
        assertEquals(text.trim(), ConsoleDigest.of(text))
    }

    @Test
    fun `leaves distinct diagnostics alone`() {
        val text = "e: Foo.kt:1:1: Unresolved reference: alpha\n" +
            "e: Foo.kt:2:2: Unresolved reference: beta\n" +
            "e: Foo.kt:3:3: Unresolved reference: gamma\n"

        assertEquals(text.trim(), ConsoleDigest.of(text))
    }

    @Test
    fun `leaves assertion messages that differ only in their values alone`() {
        val text = "expected:<3> but was:<4>\nexpected:<17> but was:<92>\nexpected:<5> but was:<6>\n"

        assertEquals(text.trim(), ConsoleDigest.of(text))
    }

    @Test
    fun `collapses identical download progress into one line and a count`() {
        val line = "Downloading https://download.jetbrains.com/idea/idea-2026.2.1.win.zip (1.61 GB / 1.61 GB)"
        val digest = ConsoleDigest.of(List(4183) { line }.joinToString("\n"))

        assertTrue(digest, digest.startsWith(line))
        assertTrue(digest, digest.contains("... and 4182 more identical line(s)"))
        assertEquals("expected a line and a count:\n$digest", 2, digest.lines().size)
    }

    @Test
    fun `front-codes a compiler cascade into a template and its positions`() {
        val positions = listOf("44:58", "44:71", "44:72", "45:5", "47:5", "47:22", "52:25")
        val text = positions.joinToString("\n") {
            "e: file:///$projectPath/build.gradle.kts:$it: Unexpected symbol"
        }

        val digest = ConsoleDigest.of(text, projectPath)

        assertTrue(digest, digest.contains("build.gradle.kts:\u2026: Unexpected symbol  [7 lines]"))
        for (position in positions) {
            assertTrue("$position missing from:\n$digest", digest.contains(position))
        }
        assertFalse("path was not shortened:\n$digest", digest.contains("file:///"))
        assertEquals("expected a header and one position line:\n$digest", 2, digest.lines().size)
    }

    @Test
    fun `lists the first positions of a long cascade and the last`() {
        val text = (1..400).joinToString("\n") { "e: build.gradle.kts:$it:9: Unexpected symbol" }
        val digest = ConsoleDigest.of(text)

        assertTrue(digest, digest.contains("build.gradle.kts:\u2026:9: Unexpected symbol  [400 lines]"))
        assertTrue(digest, digest.contains("1, 2, 3,"))
        assertTrue(digest, digest.contains("... and 388 more, last: 400"))
    }

    @Test
    fun `does not cut a shared prefix through the middle of a token`() {
        val text = (100..139).joinToString("\n") {
            "at org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.run(VerifyPluginTask.kt:$it)"
        }

        val digest = ConsoleDigest.of(text)

        assertTrue(digest, digest.contains("VerifyPluginTask.kt:\u2026)"))
        assertTrue(digest, digest.contains("100, 101, 102"))
        assertTrue(digest, digest.contains("last: 139"))
    }

    @Test
    fun `front-codes long similar lines under a shared header`() {
        val text = listOf(
            "at com.github.piotrszybicki.independentintelijaiplugin.tools.ConfigurationRunner.run(Runner.kt:47)",
            "at com.github.piotrszybicki.independentintelijaiplugin.tools.RunConfigurationTool.execute(Tool.kt:88)",
            "at com.github.piotrszybicki.independentintelijaiplugin.tools.StartDebugConfigurationTool.go(Dbg.kt:12)",
        ).joinToString("\n")

        val rendered = ConsoleDigest.of(text).lines()

        assertEquals(rendered.joinToString("\n"), 4, rendered.size)
        assertTrue(rendered[0], rendered[0].startsWith("at com.github.piotrszybicki.independentintelijaiplugin.tools.\u2026"))
        assertTrue(rendered[0], rendered[0].endsWith("[3 lines]"))
        assertEquals("  ConfigurationRunner.run(Runner.kt:47)", rendered[1])
        assertFalse(rendered[2], rendered[2].contains("independentintelijaiplugin"))
    }

    @Test
    fun `hoists a shared suffix out of the repeated lines`() {
        val text = listOf(
            "  Deprecated API usage: com.intellij.openapi.util.text.StringUtil.notNullize is deprecated",
            "  Deprecated API usage: com.intellij.execution.ui.ConsoleView.attachToProcess is deprecated",
            "  Deprecated API usage: com.intellij.ide.plugins.PluginManagerCore.isDisabled is deprecated",
            "  Deprecated API usage: com.intellij.util.ui.UIUtil.getLabelFont is deprecated",
        ).joinToString("\n")

        val rendered = ConsoleDigest.of(text).lines()

        assertEquals(rendered.joinToString("\n"), 5, rendered.size)
        assertEquals("Deprecated API usage: com.intellij.\u2026 is deprecated  [4 lines]", rendered[0])
        assertEquals("  openapi.util.text.StringUtil.notNullize", rendered[1])
        assertEquals("  util.ui.UIUtil.getLabelFont", rendered[4])
    }

    @Test
    fun `keeps only the final state of a carriage-return progress line`() {
        val digest = ConsoleDigest.of("Downloading 1%\rDownloading 40%\rDownloading 100%\ndone\n")

        assertEquals("Downloading 100%\ndone", digest)
    }

    @Test
    fun `collapses a stretch of blank lines`() {
        assertEquals("a\n\nb", ConsoleDigest.of("a\n\n\n\n\n\nb\n"))
    }

    @Test
    fun `suppresses a repeated shape that keeps coming back`() {
        val noise = List(50) { "Downloading idea.zip (1.61 GB / 1.61 GB)" }.joinToString("\n")
        val text = (1..8).joinToString("\n") { "$noise\n> Task :verifyPlugin$it" }

        val digest = ConsoleDigest.of(text)

        assertTrue(digest, digest.contains("further repetitive line(s) suppressed"))
        assertTrue(digest, digest.contains("> Task :verifyPlugin8"))
    }

    @Test
    fun `keeps the tail when the retained window overflows`() {
        val random = java.util.Random(7)
        val lines = (1..4000).map { "line " + java.lang.Long.toHexString(random.nextLong()) }
        val digest = ConsoleDigest.of(lines.joinToString("\n"))

        assertTrue(digest, digest.startsWith("[..."))
        assertTrue(digest, digest.contains("earlier line(s) omitted"))
        assertTrue(digest, digest.trimEnd().endsWith(lines.last()))
        assertTrue("digest was ${digest.length} chars", digest.length < 25_000)
    }

    @Test
    fun `reports nothing for no output`() {
        assertEquals("", ConsoleDigest.of(""))
        assertEquals("", ConsoleDigest.of("\n\n\n"))
    }

    @Test
    fun `strips ansi colour codes`() {
        assertEquals("FAILED", ConsoleDigest.of("\u001B[31mFAILED\u001B[0m\n"))
    }
}
