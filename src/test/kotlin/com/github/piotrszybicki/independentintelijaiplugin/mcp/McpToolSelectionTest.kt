package com.github.piotrszybicki.independentintelijaiplugin.mcp

import org.junit.Assert.assertEquals
import org.junit.Test

class McpToolSelectionTest {
    @Test
    fun `remembers selected and explicitly cleared tools over defaults`() {
        val selection = McpToolSelection()
        assertEquals(emptySet<String>(), selection.enabled(emptyList()))
        assertEquals(setOf("one"), selection.enabled(listOf("one")))
        selection.set(listOf("two"))
        assertEquals(setOf("two"), selection.enabled(listOf("one")))
        selection.set(emptyList())
        assertEquals(emptySet<String>(), selection.enabled(listOf("one")))
    }
}
