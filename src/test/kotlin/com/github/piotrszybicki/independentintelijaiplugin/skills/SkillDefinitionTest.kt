package com.github.piotrszybicki.independentintelijaiplugin.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class SkillDefinitionTest {
    @get:org.junit.Rule
    val temp = org.junit.rules.TemporaryFolder()

    @Test
    fun `falls back to the directory name and the first paragraph`() {
        val file =
            skillFile(
                "summarize-changes",
                """
                ---
                model: inherit
                ---

                # Heading

                Summarizes uncommitted changes and flags anything risky.

                More detail nobody needs yet.
                """.trimIndent(),
            )
        val skill = SkillDefinition.read(file)!!
        assertEquals("summarize-changes", skill.name)
        assertEquals("Summarizes uncommitted changes and flags anything risky.", skill.description)
    }

    @Test
    fun `appends when_to_use to the description`() {
        val file =
            skillFile(
                "deploy",
                "---\ndescription: Deploys the app.\nwhen_to_use: Use when asked to ship or release.\n---\n",
            )
        assertEquals("Deploys the app. Use when asked to ship or release.", SkillDefinition.read(file)!!.description)
    }

    @Test
    fun `skips a file with no description and no body`() {
        assertNull(SkillDefinition.read(skillFile("empty", "---\nmodel: inherit\n---\n")))
    }

    private fun skillFile(
        name: String,
        content: String,
    ): File {
        val directory = temp.newFolder(name)
        return File(directory, "SKILL.md").apply { writeText(content) }
    }
}
