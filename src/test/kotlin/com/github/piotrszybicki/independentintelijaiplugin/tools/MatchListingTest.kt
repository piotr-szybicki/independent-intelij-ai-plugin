package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.github.piotrszybicki.independentintelijaiplugin.tools.MatchListing.Match
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchListingTest {

    @Test
    fun `puts every hit in a file under one heading, in line order`() {
        val listing = MatchListing.format(
            "3 match(es) for \"settings\"",
            listOf(
                Match("src/mcp/McpService.kt", 64, "val text = state.mcpServers"),
                Match("src/tools/ToolCatalog.kt", 4, "import ...AICodingAgentSettingsState"),
                Match("src/mcp/McpService.kt", 8, "import ...AICodingAgentSettingsState"),
            ),
            truncated = false,
        )

        assertEquals(
            """
            3 match(es) for "settings":
            src/mcp/McpService.kt
              8: import ...AICodingAgentSettingsState
              64: val text = state.mcpServers
            src/tools/ToolCatalog.kt
              4: import ...AICodingAgentSettingsState

            """.trimIndent(),
            listing,
        )
    }

    @Test
    fun `prints a line once however many times it was reported`() {
        val duplicated = listOf(
            Match("src/mcp/McpService.kt", 8, "import ...State"),
            Match("src/mcp/McpService.kt", 8, "import ...State"),
        )

        assertEquals(1, MatchListing.count(duplicated))
        assertEquals(
            """
            1 match(es) for "settings":
            src/mcp/McpService.kt
              8: import ...State

            """.trimIndent(),
            MatchListing.format("1 match(es) for \"settings\"", duplicated, truncated = false),
        )
    }

    @Test
    fun `omits the source line when there is none`() {
        val listing = MatchListing.format("1 match(es)", listOf(Match("src/A.kt", 12)), truncated = false)

        assertEquals("1 match(es):\nsrc/A.kt\n  12\n", listing)
    }

    @Test
    fun `says the search stopped at the limit rather than running out of matches`() {
        val listing = MatchListing.format("1 match(es)", listOf(Match("src/A.kt", 12)), truncated = true)

        assertTrue(listing, listing.lineSequence().first().contains("stopped at the limit"))
    }
}
