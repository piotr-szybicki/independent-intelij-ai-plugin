package com.github.piotrszybicki.independentintelijaiplugin.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
        val roots =
            SkillRoot.parseAll(
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
