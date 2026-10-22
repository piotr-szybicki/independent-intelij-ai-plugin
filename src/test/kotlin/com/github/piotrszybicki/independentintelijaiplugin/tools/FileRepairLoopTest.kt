package com.github.piotrszybicki.independentintelijaiplugin.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FileRepairLoopTest {
    @Test
    fun `clean files return immediately without a model request or write`() {
        assertEquals(
            "No problems Found",
            FileRepairLoop(
                read = { "clean" },
                analyse = { "No problems at severity warning or above in test.kt." },
                repair = { _, _ -> error("must not call model") },
                replace = { _, _ -> error("must not write") },
                isCancelled = { false },
            ).run(),
        )
    }

    @Test
    fun `repeats full file repair and fresh analysis until clean`() {
        var file = "whole original file"
        val sent = mutableListOf<String>()
        var analyses = 0
        val result =
            FileRepairLoop(
                read = { file },
                analyse = {
                    analyses++
                    if (file ==
                        "whole fixed file"
                    ) {
                        "No problems at severity warning or above in test.kt."
                    } else {
                        "warning"
                    }
                },
                repair = { content, problems ->
                    sent += content
                    assertEquals("warning", problems)
                    if (sent.size == 1) "whole intermediate file" else "whole fixed file"
                },
                replace = { original, replacement ->
                    assertEquals(file, original)
                    file = replacement
                },
                isCancelled = { false },
            ).run()
        assertEquals("Problems were detected and fixed", result)
        assertEquals(listOf("whole original file", "whole intermediate file"), sent)
        assertEquals(3, analyses)
        assertEquals("whole fixed file", file)
    }

    @Test
    fun `analysis failure does not call model or claim success`() {
        val failure =
            assertThrows(IllegalStateException::class.java) {
                FileRepairLoop(
                    read = { "file" },
                    analyse = { "Error: analysis failed" },
                    repair = { _, _ -> error("must not call model") },
                    replace = { _, _ -> error("must not write") },
                    isCancelled = { false },
                ).run()
            }
        assertEquals("analysis failed", failure.message)
    }

    @Test
    fun `cancellation during model request prevents replacement`() {
        var cancelled = false
        val failure =
            assertThrows(IllegalStateException::class.java) {
                FileRepairLoop(
                    read = { "original" },
                    analyse = { "warning" },
                    repair = { _, _ ->
                        cancelled = true
                        "replacement"
                    },
                    replace = { _, _ -> error("must not write") },
                    isCancelled = { cancelled },
                ).run()
            }
        assertTrue(failure.message.orEmpty().contains("cancelled"))
    }

    @Test
    fun `concurrent edit during analysis prevents false clean result`() {
        var file = "original"
        assertThrows(IllegalStateException::class.java) {
            FileRepairLoop(
                read = { file },
                analyse = {
                    file = "user edit"
                    "No problems at severity warning or above in test.kt."
                },
                repair = { _, _ -> error("must not call model") },
                replace = { _, _ -> error("must not write") },
                isCancelled = { false },
            ).run()
        }
        assertEquals("user edit", file)
    }

    @Test
    fun `empty unchanged and cycling responses stop without another write`() {
        for (response in listOf("", "  ", "original")) {
            assertThrows(IllegalStateException::class.java) {
                FileRepairLoop(
                    read = { "original" },
                    analyse = { "warning" },
                    repair = { _, _ -> response },
                    replace = { _, _ -> error("must not write") },
                    isCancelled = { false },
                ).run()
            }
        }
        var file = "original"
        var writes = 0
        assertThrows(IllegalStateException::class.java) {
            FileRepairLoop(
                read = { file },
                analyse = { "warning" },
                repair = { content, _ -> if (content == "original") "second" else "original" },
                replace = { _, replacement ->
                    file = replacement
                    writes++
                },
                isCancelled = { false },
            ).run()
        }
        assertEquals(1, writes)
    }
}
