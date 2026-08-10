package com.github.piotrszybicki.independentintelijaiplugin.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Covers the frontmatter reader, which is the part of the skills support with nowhere to hide: it is
 * a hand-written parser for a corner of YAML, run against files this plugin did not write.
 */
class SkillFrontmatterTest {

    @Test
    fun `reads plain and quoted scalars`() {
        val fields = SkillFrontmatter.parse(
            """
            ---
            name: deploy
            description: "Ships the app to production"
            argument-hint: '[environment]'
            ---

            Body text.
            """.trimIndent(),
        )

        assertEquals("deploy", fields["name"])
        assertEquals("Ships the app to production", fields["description"])
        assertEquals("[environment]", fields["argument-hint"])
    }

    @Test
    fun `reads folded and literal block scalars`() {
        val fields = SkillFrontmatter.parse(
            """
            ---
            description: >
              Use when the user asks what changed
              or wants a commit message.
            notes: |
              first
              second
            ---
            """.trimIndent(),
        )

        assertEquals("Use when the user asks what changed or wants a commit message.", fields["description"])
        assertEquals("first\nsecond", fields["notes"])
    }

    @Test
    fun `ignores nested keys`() {
        val fields = SkillFrontmatter.parse(
            """
            ---
            name: reviewer
            metadata:
              name: not-the-skill-name
              owner: platform
            allowed-tools:
              - Read
              - Grep
            ---
            """.trimIndent(),
        )

        assertEquals("reviewer", fields["name"])
        assertEquals(null, fields["owner"])
    }

    /** Files written on Windows arrive with CRLF, and the fence has to match all the same. */
    @Test
    fun `tolerates carriage returns`() {
        val fields = SkillFrontmatter.parse("---\r\nname: windows\r\ndescription: works\r\n---\r\n")

        assertEquals("windows", fields["name"])
        assertEquals("works", fields["description"])
    }

    @Test
    fun `ignores a horizontal rule that is not frontmatter`() {
        val fields = SkillFrontmatter.parse(
            """
            # A plain document

            ---

            name: not-a-field
            """.trimIndent(),
        )

        assertTrue(fields.isEmpty())
    }

    @Test
    fun `ignores an unterminated fence`() {
        assertTrue(SkillFrontmatter.parse("---\nname: dangling\n\nprose that never closes").isEmpty())
    }

    @Test
    fun `strips a trailing comment from an unquoted value`() {
        val fields = SkillFrontmatter.parse("---\nname: deploy # internal only\n---\n")

        assertEquals("deploy", fields["name"])
    }

    /**
     * A hash after whitespace opens a comment in YAML even when it was meant as part of the text,
     * so a description containing one has to be quoted -- the same as it would be for any real YAML
     * parser, which is the behaviour to match if this is ever swapped for one.
     */
    @Test
    fun `treats an unquoted hash as a comment and a quoted one as text`() {
        assertEquals("Fixes issue", SkillFrontmatter.parse("---\ndescription: Fixes issue #42\n---\n")["description"])
        assertEquals(
            "Fixes issue #42",
            SkillFrontmatter.parse("---\ndescription: \"Fixes issue #42\"\n---\n")["description"],
        )
    }
}

class SkillDefinitionTest {

    @get:org.junit.Rule
    val temp = org.junit.rules.TemporaryFolder()

    @Test
    fun `falls back to the directory name and the first paragraph`() {
        val file = skillFile(
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
        val file = skillFile(
            "deploy",
            "---\ndescription: Deploys the app.\nwhen_to_use: Use when asked to ship or release.\n---\n",
        )

        assertEquals("Deploys the app. Use when asked to ship or release.", SkillDefinition.read(file)!!.description)
    }

    /** Nothing says what it is for and nothing to fall back on -- listing it would only cost tokens. */
    @Test
    fun `skips a file with no description and no body`() {
        assertNull(SkillDefinition.read(skillFile("empty", "---\nmodel: inherit\n---\n")))
    }

    private fun skillFile(name: String, content: String): File {
        val directory = temp.newFolder(name)
        return File(directory, "SKILL.md").apply { writeText(content) }
    }
}

class SkillRootTest {

    @get:org.junit.Rule
    val temp = org.junit.rules.TemporaryFolder()

    @Test
    fun `resolves relative paths against the project and keeps absolute ones`() {
        val base = temp.newFolder("project")
        val elsewhere = temp.newFolder("elsewhere")

        val roots = SkillRoot.parseAll(".claude/skills\n${elsewhere.absolutePath}", base)

        assertEquals(2, roots.size)
        assertEquals(File(base, ".claude/skills").canonicalPath, roots[0].directory!!.path)
        assertEquals(elsewhere.canonicalPath, roots[1].directory!!.path)
    }

    @Test
    fun `skips blanks and comments and drops duplicates`() {
        val base = temp.newFolder("project")

        val roots = SkillRoot.parseAll(
            """
            # personal first
            .skills

            .skills
            ${File(base, ".skills").absolutePath}
            """.trimIndent(),
            base,
        )

        assertEquals(1, roots.size)
    }

    @Test
    fun `expands a leading tilde`() {
        val roots = SkillRoot.parseAll("~/.claude/skills", null)

        val home = File(System.getProperty("user.home"), ".claude/skills").canonicalPath
        assertEquals(home, roots.single().directory!!.path)
    }

    @Test
    fun `reports a relative path with no project to resolve against`() {
        val root = SkillRoot.parseAll(".skills", null).single()

        assertNull(root.directory)
        assertTrue(root.error!!.contains("relative"))
    }
}
