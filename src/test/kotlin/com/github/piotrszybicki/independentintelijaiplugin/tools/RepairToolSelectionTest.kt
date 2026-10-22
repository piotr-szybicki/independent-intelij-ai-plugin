package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.github.piotrszybicki.independentintelijaiplugin.agents.AgentToolPolicy
import com.github.piotrszybicki.independentintelijaiplugin.settings.ConversationDefaultsConfig
import com.github.piotrszybicki.independentintelijaiplugin.settings.ConversationTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepairToolSelectionTest {
    @Test
    fun `catalog removes old tools and preserves saved diagnostic selection`() {
        val names = ToolCatalog.entries.map { it.name }
        assertTrue("analyse_and_fix" in names)
        assertFalse("get_file_problems" in names)
        assertFalse("apply_quick_fix" in names)
        assertEquals(setOf("analyse_and_fix"), ToolCatalog.parse("get_file_problems,apply_quick_fix"))
    }

    @Test
    fun `legacy conversation defaults overrides and agent policies migrate`() {
        assertEquals(setOf("analyse_and_fix"), ConversationTools(tools = listOf("get_file_problems")).toolNames)
        assertEquals(
            listOf("analyse_and_fix"),
            ConversationDefaultsConfig
                .parse(
                    """{"conversation-defaults":{"tools":["get_file_problems"]}}""",
                ).tools,
        )
        assertTrue(AgentToolPolicy.parse("get_file_problems").keeps("analyse_and_fix"))
        assertTrue("analyse_and_fix" in AgentToolPolicy.parse("+get_file_problems").additions)
        assertFalse(AgentToolPolicy.parse("-get_file_problems").keeps("analyse_and_fix"))
    }
}
