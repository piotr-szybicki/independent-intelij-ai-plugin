package com.github.piotrszybicki.independentintelijaiplugin.cleanup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which comments the sweep takes, and what the file looks like once they are gone.
 *
 * The two things worth pinning are the boundary -- a line comment or a plain block comment is not
 * documentation and must survive -- and that a removed comment leaves no scar: no blank indented
 * line where it stood, no doubled space where it sat inline.
 */
class JavadocCommentsTest {

    /** Applies the removals the sweep would make, as it does: back to front. */
    private fun strip(source: String, onlyBlank: Boolean = false): String {
        val comments = Regex("""/\*\*.*?\*/""", RegexOption.DOT_MATCHES_ALL).findAll(source)
        var result = source
        for (match in comments.toList().asReversed()) {
            if (onlyBlank && !JavadocComments.isBlank(match.value)) continue
            if (!JavadocComments.isJavadoc(match.value)) continue
            val removal = JavadocComments.removalFor(source, match.range.first, match.range.last + 1)
            result = result.removeRange(removal.start, removal.end)
        }
        return result
    }

    @Test
    fun `recognises the javadoc form and nothing else`() {
        assertTrue(JavadocComments.isJavadoc("/** a */"))
        assertTrue(JavadocComments.isJavadoc("/**\n * a\n */"))
        assertFalse("a block comment is not documentation", JavadocComments.isJavadoc("/* a */"))
        assertFalse("nor is a line comment", JavadocComments.isJavadoc("// a"))
        assertFalse("nor is the empty block comment", JavadocComments.isJavadoc("/**/"))
        assertFalse("an unterminated comment is not one either", JavadocComments.isJavadoc("/** a"))
    }

    @Test
    fun `an empty stub is blank, one holding a tag is not`() {
        assertTrue(JavadocComments.isBlank("/**\n *\n */"))
        assertTrue(JavadocComments.isBlank("/** */"))
        assertTrue(JavadocComments.isBlank("/**\n *\n *\n */"))
        assertFalse(JavadocComments.isBlank("/**\n * @param x the thing\n */"))
        assertFalse(JavadocComments.isBlank("/** Why this exists. */"))
    }

    @Test
    fun `a comment on its own lines takes the lines with it`() {
        val source = """
            class A {
                /**
                 * What this does.
                 */
                fun a() {}
            }
        """.trimIndent()

        assertEquals(
            """
            class A {
                fun a() {}
            }
            """.trimIndent(),
            strip(source),
        )
    }

    @Test
    fun `a comment sharing a line with code keeps the line`() {
        assertEquals("fun a(x: Int) {}", strip("fun a(/** the thing */ x: Int) {}"))
    }

    @Test
    fun `line and block comments survive the sweep`() {
        val source = """
            // still here
            /* and this */
            /** but not this */
            val a = 1
        """.trimIndent()

        assertEquals(
            """
            // still here
            /* and this */
            val a = 1
            """.trimIndent(),
            strip(source),
        )
    }

    @Test
    fun `report-only mode aside, the blank filter leaves written documentation alone`() {
        val source = """
            /**
             *
             */
            fun a() {}

            /**
             * Worth saying.
             */
            fun b() {}
        """.trimIndent()

        assertEquals(
            """
            fun a() {}

            /**
             * Worth saying.
             */
            fun b() {}
            """.trimIndent(),
            strip(source, onlyBlank = true),
        )
    }

    @Test
    fun `a comment ending the file leaves no trailing blank line`() {
        assertEquals("val a = 1\n", strip("val a = 1\n/** trailing */\n"))
        assertEquals("val a = 1\n", strip("val a = 1\n/** no newline after me */"))
    }

    @Test
    fun `a comment opening the file leaves nothing above the code`() {
        assertEquals("package a\n", strip("/** A file comment. */\npackage a\n"))
    }
}
