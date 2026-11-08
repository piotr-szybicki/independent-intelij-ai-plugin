package com.github.piotrszybicki.independentintelijaiplugin.agents

import com.github.piotrszybicki.independentintelijaiplugin.settings.AgentRosterConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMcpSelectionTest {
    @Test
    fun `agent MCP selectors round trip and match only the intended server`() {
        val roster =
            AgentRosterConfig.parse(
                """{"agents":[{"name":"github-agent","mcp-tools":["github/*"]}]}""",
            )
        val selectors = roster.agents.single().mcpTools!!
        assertEquals(
            selectors,
            AgentRosterConfig
                .parse("""{"agents":${roster.toJson()}}""")
                .agents
                .single()
                .mcpTools,
        )
        assertTrue(AgentMcpSelection.includes(selectors, "github", "mcp__github__search"))
        assertFalse(AgentMcpSelection.includes(selectors, "other", "mcp__other__search"))
        assertTrue(AgentMcpSelection.includes(listOf("*"), "other", "mcp__other__search"))
        assertTrue(AgentMcpSelection.includes(listOf("mcp__github__search"), "github", "mcp__github__search"))
    }

    @Test
    fun `missing MCP list inherits and empty list selects none`() {
        val roster = AgentRosterConfig.parse("""{"agents":[{"name":"a"},{"name":"b","mcp-tools":[]}]}""")
        assertNull(roster.agents[0].mcpTools)
        assertEquals(emptyList<String>(), roster.agents[1].mcpTools)
    }
}
