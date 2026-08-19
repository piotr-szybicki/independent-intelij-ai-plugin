package com.github.piotrszybicki.independentintelijaiplugin.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRosterConfigTest {

    @Test
    fun `reads the agents and their tools`() {
        val roster = AgentRosterConfig.parse(
            """
            {
              "agents": [
                {
                  "name": "coding-agent",
                  "tools": ["read_project_file", "edit_file_lines", "create_file"],
                  "model": "claude-opus-5"
                },
                { "name": "review-agent", "tools": ["read_project_file"] }
              ],
              "configurations": []
            }
            """.trimIndent(),
        )

        assertEquals(2, roster.agents.size)
        val coding = roster.forAgent("coding-agent")!!
        assertEquals(listOf("read_project_file", "edit_file_lines", "create_file"), coding.tools)
        assertEquals("claude-opus-5", coding.model)
        assertEquals("", coding.configurationName)
        assertNull(roster.forAgent("nobody"))
    }

    @Test
    fun `a file with no agents section is not an error`() {
        assertTrue(AgentRosterConfig.parse("""{"configurations": []}""").agents.isEmpty())
        assertTrue(AgentRosterConfig.parse("").agents.isEmpty())
    }

    @Test
    fun `an entry with no name is rejected`() {
        val failure = assertThrows(AgentConfigurationException::class.java) {
            AgentRosterConfig.parse("""{"agents": [{"tools": ["read_project_file"]}]}""")
        }

        assertTrue(failure.message!!.contains("name"))
    }

    @Test
    fun `tools must be an array`() {
        assertThrows(AgentConfigurationException::class.java) {
            AgentRosterConfig.parse("""{"agents": [{"name": "a", "tools": "read_project_file"}]}""")
        }
    }

    @Test
    fun `the same agent cannot be listed twice`() {
        assertThrows(AgentConfigurationException::class.java) {
            AgentRosterConfig.parse("""{"agents": [{"name": "a"}, {"name": "a"}]}""")
        }
    }

    @Test
    fun `what it renders is what it reads back`() {
        val roster = AgentRosterConfig(
            listOf(
                AgentRosterEntry(
                    name = "coding-agent",
                    description = "Implements a spec.",
                    prompt = "",
                    tools = listOf("*", "-run_shell_command"),
                    configurationName = "Anthropic Claude",
                    model = "claude-opus-5",
                ),
            ),
        )

        val rendered = AgentConfiguration.render(
            configurations = listOf(AgentConfiguration.DEFAULT),
            agents = roster,
        )

        assertEquals(roster, AgentRosterConfig.parse(rendered))
    }
}
