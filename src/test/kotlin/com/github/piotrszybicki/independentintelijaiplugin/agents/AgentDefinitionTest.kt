package com.github.piotrszybicki.independentintelijaiplugin.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AgentDefinitionTest {
    @get:org.junit.Rule
    val temp = org.junit.rules.TemporaryFolder()

    @Test
    fun `reads the frontmatter and takes the body as the prompt`() {
        val file =
            agentFile(
                "test-writer",
                """
                ---
                description: Writes the tests for a change that is already made.
                tools: read_project_file, create_file
                configuration: anthropic
                model: claude-sonnet-4-5
                ---

                You write tests, and nothing else.
                """.trimIndent(),
            )

        val agent = AgentDefinition.read(file)!!

        assertEquals("test-writer", agent.name)
        assertEquals("Writes the tests for a change that is already made.", agent.description)
        assertEquals("You write tests, and nothing else.", agent.prompt)
        assertEquals("anthropic", agent.configurationName)
        assertEquals("claude-sonnet-4-5", agent.model)
        assertTrue(agent.tools.keeps("create_file"))
        assertFalse(agent.tools.keeps("delete_file"))
        assertTrue(agent.isFromFile)
    }

    @Test
    fun `falls back to the directory name and the first paragraph`() {
        val file = agentFile("migrator", "Moves the code from one API to another.\n\nDetail below.")

        val agent = AgentDefinition.read(file)!!

        assertEquals("migrator", agent.name)
        assertEquals("Moves the code from one API to another.", agent.description)
        assertTrue(agent.tools.keeps("run_shell_command"))
    }

    @Test
    fun `skips a file with nothing below the frontmatter`() {
        assertNull(AgentDefinition.read(agentFile("empty", "---\ndescription: Does nothing.\n---\n")))
    }

    private fun agentFile(
        name: String,
        content: String,
    ): File {
        val directory = temp.newFolder(name)
        return File(directory, "AGENT.md").apply { writeText(content) }
    }
}
