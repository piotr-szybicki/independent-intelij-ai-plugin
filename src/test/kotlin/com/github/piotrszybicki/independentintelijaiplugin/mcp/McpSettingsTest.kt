package com.github.piotrszybicki.independentintelijaiplugin.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class McpSettingsTest {
    @Test
    fun `reads editable tool list and distinguishes absent from empty`() {
        val servers = McpServerConfig.parseSettings(
            """{"mcpServers":{"one":{"url":"https://example.com","tools":["read", "write"]},
                "two":{"url":"https://example.com","tools":[]},"three":{"url":"https://example.com"}}}""",
        )
        assertEquals(listOf("read", "write"), servers[0].tools)
        assertEquals(emptyList<String>(), servers[1].tools)
        assertEquals(null, servers[2].tools)
        assertThrows(McpConfigException::class.java) {
            McpServerConfig.parseSettings("""{"mcpServers":{"one":{"url":"https://example.com","tools":[1]}}}""")
        }
    }

    @Test
    fun `reads servers alongside agent configurations`() {
        val servers =
            McpServerConfig.parseSettings(
                """{"configurations": [], "mcpServers": {"github": {
              "url": "https://api.githubcopilot.com/mcp/",
              "headers": {"Authorization": "Bearer ${'$'}{env:GITHUB_PAT}"}
            }}}""",
            )
        assertEquals(1, servers.size)
        assertEquals("github", servers.single().name)
        assertEquals("Bearer ${'$'}{env:GITHUB_PAT}", servers.single().headers["Authorization"])
    }

    @Test
    fun `missing servers are empty and malformed section is reported`() {
        assertTrue(McpServerConfig.parseSettings("""{"configurations": []}""").isEmpty())
        assertThrows(McpConfigException::class.java) {
            McpServerConfig.parseSettings("""{"mcpServers": []}""")
        }
    }
}
