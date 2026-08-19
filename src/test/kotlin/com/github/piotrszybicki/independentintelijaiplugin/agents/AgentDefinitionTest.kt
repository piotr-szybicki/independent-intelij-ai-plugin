package com.github.piotrszybicki.independentintelijaiplugin.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AgentToolPolicyTest {

    @Test
    fun `no value inherits what the chat already has`() {
        val policy = AgentToolPolicy.parse(null)

        assertTrue(policy.saysNothing)
    }

    @Test
    fun `a star takes every tool rather than inheriting`() {
        val policy = AgentToolPolicy.parse("*")

        assertFalse(policy.saysNothing)
        assertTrue(policy.keeps("edit_file_lines"))
        assertTrue(policy.keeps("run_shell_command"))
    }

    @Test
    fun `a list keeps only what it names`() {
        val policy = AgentToolPolicy.parse("read_project_file, find_in_files")

        assertTrue(policy.keeps("read_project_file"))
        assertFalse(policy.keeps("delete_file"))
    }

    @Test
    fun `a minus takes one tool away from all of them`() {
        val policy = AgentToolPolicy.parse("*, -run_shell_command")

        assertTrue(policy.keeps("edit_file_lines"))
        assertFalse(policy.keeps("run_shell_command"))
    }

    @Test
    fun `a minus on its own still keeps the rest`() {
        val policy = AgentToolPolicy.parse("-delete_file")

        assertFalse(policy.saysNothing)
        assertTrue(policy.keeps("create_file"))
        assertFalse(policy.keeps("delete_file"))
    }

    @Test
    fun `the built-in coding agent can write from the start`() {
        val coding = AgentCatalog.BUILT_IN.first { it.name == AgentCatalog.CODING_AGENT }

        assertFalse(coding.tools.saysNothing)
        AgentCatalog.WRITING_TOOLS.forEach { assertTrue(it, coding.tools.keeps(it)) }
    }

    @Test
    fun `the built-in review agent cannot write`() {
        val review = AgentCatalog.BUILT_IN.first { it.name == AgentCatalog.REVIEW_AGENT }

        AgentCatalog.WRITING_TOOLS.forEach { assertFalse(it, review.tools.keeps(it)) }
        assertTrue(review.tools.keeps("read_project_file"))
    }
}

class AgentDefinitionTest {

    @get:org.junit.Rule
    val temp = org.junit.rules.TemporaryFolder()

    @Test
    fun `reads the frontmatter and takes the body as the prompt`() {
        val file = agentFile(
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
        assertFalse(agent.isBuiltIn)
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

    private fun agentFile(name: String, content: String): File {
        val directory = temp.newFolder(name)
        return File(directory, "AGENT.md").apply { writeText(content) }
    }
}
