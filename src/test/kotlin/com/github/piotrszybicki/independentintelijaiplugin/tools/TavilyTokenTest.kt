package com.github.piotrszybicki.independentintelijaiplugin.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TavilyTokenTest {
    @Test
    fun `reads a literal token`() {
        assertEquals("secret", tavilyToken("""{"tavily":{"token":" secret "}}"""))
    }

    @Test
    fun `resolves environment references`() {
        assertEquals(
            "secret",
            tavilyToken("""{"tavily":{"token":"${'$'}{SEARCH_KEY}"}}""") {
                if (it ==
                    "SEARCH_KEY"
                ) {
                    "secret"
                } else {
                    null
                }
            },
        )
        assertEquals("", tavilyToken("""{"tavily":{"token":"${'$'}MISSING"}}""") { null })
    }

    @Test
    fun `handles missing and invalid configuration`() {
        assertEquals("", tavilyToken("""{}"""))
        assertThrows(IllegalArgumentException::class.java) { tavilyToken("""{"tavily":{"token":42}}""") }
    }
}
