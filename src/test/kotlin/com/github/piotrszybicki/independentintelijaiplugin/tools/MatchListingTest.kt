package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.github.piotrszybicki.independentintelijaiplugin.tools.MatchListing.Match
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape of a search result the model reads.
 *
 * The hits arrive interleaved and duplicated from the search engine, so the two failures worth
 * pinning are a file's hits ending up under two headings and the same line being printed twice --
 * both put the model back to paying for the same path over and over, which is what the grouping is
 * for.
 */
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

    /** The engine reports one line twice; printing it twice is two identical lines of tokens. */
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

    /** Callers with only a location to report get the heading and the line number, nothing else. */
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
