package com.github.piotrszybicki.independentintelijaiplugin.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillFrontmatterTest {
    @Test
    fun `reads plain and quoted scalars`() {
        val fields =
            SkillFrontmatter.parse(
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
        val fields =
            SkillFrontmatter.parse(
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
        val fields =
            SkillFrontmatter.parse(
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

    @Test
    fun `tolerates carriage returns`() {
        val fields = SkillFrontmatter.parse("---\r\nname: windows\r\ndescription: works\r\n---\r\n")

        assertEquals("windows", fields["name"])
        assertEquals("works", fields["description"])
    }

    @Test
    fun `ignores a horizontal rule that is not frontmatter`() {
        val fields =
            SkillFrontmatter.parse(
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

    @Test
    fun `treats an unquoted hash as a comment and a quoted one as text`() {
        assertEquals("Fixes issue", SkillFrontmatter.parse("---\ndescription: Fixes issue #42\n---\n")["description"])
        assertEquals(
            "Fixes issue #42",
            SkillFrontmatter.parse("---\ndescription: \"Fixes issue #42\"\n---\n")["description"],
        )
    }
}
