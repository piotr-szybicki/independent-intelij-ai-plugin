package com.github.piotrszybicki.independentintelijaiplugin.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationDefaultsConfigTest {
    @Test
    fun `delegation follows chat override and configured defaults`() {
        val inherited = ConversationTools.INHERIT
        assertFalse(inherited.allowsDelegation(listOf("read_project_file"), false))
        assertFalse(inherited.allowsDelegation(emptyList(), false))
        assertTrue(inherited.allowsDelegation(listOf("delegate_to_agent"), false))
        assertTrue(inherited.allowsDelegation(null, false))
        assertFalse(inherited.allowsDelegation(listOf("delegate_to_agent"), true))
        assertFalse(ConversationTools(overridden = true).allowsDelegation(null, false))
        assertTrue(ConversationTools(overridden = true, tools = listOf("delegate_to_agent"))
            .allowsDelegation(emptyList(), false))
    }

    @Test
    fun `output limit and check are independent`() {
        val defaults =
            ConversationDefaultsConfig.parse(
                """{"conversation-defaults":{"max-tool-output-tokens":0,"check-tool-output-tokens":false}}""",
            )
        assertEquals(0, defaults.maxToolOutputTokens)
        assertEquals(false, defaults.checkToolOutputTokens)
        assertEquals(defaults, ConversationDefaultsConfig.parse("""{"conversation-defaults":${defaults.toJson()}}"""))
        assertThrows(AgentConfigurationException::class.java) {
            ConversationDefaultsConfig.parse("""{"conversation-defaults":{"check-tool-output-tokens":"false"}}""")
        }
    }

    @Test
    fun `reads all three arrays`() {
        val defaults =
            ConversationDefaultsConfig.parse(
                """
                {
                  "conversation-defaults": {
                    "tools": ["read_project_file", "find_in_files"],
                    "mcp-tools": ["mcp__github__search_issues"],
                    "skills": ["write-tests"]
                  },
                  "configurations": []
                }
                """.trimIndent(),
            )

        assertEquals(listOf("read_project_file", "find_in_files"), defaults.tools)
        assertEquals(listOf("mcp__github__search_issues"), defaults.mcpTools)
        assertEquals(listOf("write-tests"), defaults.skills)
    }

    @Test
    fun `a missing array is not the same as an empty one`() {
        val missing = ConversationDefaultsConfig.parse("""{"conversation-defaults": {"skills": []}}""")
        assertNull("no tools key means the settings page decides", missing.tools)
        assertNull(missing.mcpTools)

        val empty =
            ConversationDefaultsConfig.parse(
                """{"conversation-defaults": {"tools": [], "mcp-tools": []}}""",
            )
        assertEquals("an empty array means none of them", emptyList<String>(), empty.tools)
        assertEquals(emptyList<String>(), empty.mcpTools)
    }

    @Test
    fun `a file with no section at all leaves everything to the settings page`() {
        assertEquals(ConversationDefaultsConfig.DEFAULT, ConversationDefaultsConfig.parse("""{"configurations": []}"""))
        assertEquals(ConversationDefaultsConfig.DEFAULT, ConversationDefaultsConfig.parse(""))
        assertTrue(ConversationDefaultsConfig.DEFAULT.saysNothing)
    }

    @Test
    fun `the section and its arrays have to be the right shape`() {
        assertThrows(AgentConfigurationException::class.java) {
            ConversationDefaultsConfig.parse("""{"conversation-defaults": ["read_project_file"]}""")
        }
        assertThrows(AgentConfigurationException::class.java) {
            ConversationDefaultsConfig.parse("""{"conversation-defaults": {"tools": "read_project_file"}}""")
        }
        assertThrows(AgentConfigurationException::class.java) {
            ConversationDefaultsConfig.parse("""{"conversation-defaults": {"skills": [{"name": "a"}]}}""")
        }
    }

    @Test
    fun `what it renders is what it reads back`() {
        val defaults =
            ConversationDefaultsConfig(
                tools = listOf("read_project_file"),
                mcpTools = null,
                skills = listOf("write-tests"),
            )

        val rendered =
            AgentConfiguration.render(
                configurations = listOf(AgentConfiguration.DEFAULT),
                conversationDefaults = defaults,
            )

        assertEquals(defaults, ConversationDefaultsConfig.parse(rendered))
    }
}
