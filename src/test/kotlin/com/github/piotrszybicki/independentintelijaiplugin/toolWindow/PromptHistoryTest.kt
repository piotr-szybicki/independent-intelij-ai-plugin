package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PromptHistoryTest {
    @Test
    fun `moves backward and forward through submitted prompts`() {
        val history = PromptHistory()
        history.record("first")
        history.record("second")

        assertEquals("second", history.previous(""))
        assertEquals("first", history.previous("second"))
        assertEquals("first", history.previous("first"))
        assertEquals("second", history.next())
    }

    @Test
    fun `restores the unsent draft after the newest prompt`() {
        val history = PromptHistory()
        history.record("submitted")

        assertEquals("submitted", history.previous("unfinished draft"))
        assertEquals("unfinished draft", history.next())
        assertNull(history.next())
    }

    @Test
    fun `restores persisted prompts and ignores blank submissions`() {
        val history = PromptHistory()
        history.restore(listOf("older", "newer"))
        history.record("   ")

        assertEquals(listOf("older", "newer"), history.snapshot)
        assertEquals("newer", history.previous(""))
    }

    @Test
    fun `recording after navigation starts from the new end`() {
        val history = PromptHistory()
        history.record("first")
        history.previous("draft")
        history.record("second")

        assertEquals("second", history.previous(""))
        assertEquals("first", history.previous("second"))
    }
}
