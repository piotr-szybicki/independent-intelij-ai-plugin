package com.github.piotrszybicki.independentintelijaiplugin.cleanup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which comments each choice claims, and what the line looks like once a comment leaves it.
 *
 * The marker cases are the ones that matter most: a `comment_id` line deleted by accident does not
 * lose a line of text, it strands the paragraph in the database that line stood for.
 */
class DeleteCommentsTest {

    private fun plan(choice: CommentChoice, includeMarkers: Boolean = false) =
        DeleteComments(choice, includeMarkers)

    /** Applies what the plan would do to the single comment spanning [start, end). */
    private fun cut(source: String, start: Int, end: Int): String {
        val removal = JavadocComments.removalFor(source, start, end)
        return source.removeRange(removal.start, removal.end)
    }

    private fun cutTrailing(source: String, comment: String): String {
        val start = source.indexOf(comment)
        return cut(source, start, start + comment.length)
    }

    @Test
    fun `javadoc choices leave line comments alone`() {
        assertTrue(plan(CommentChoice.JAVADOC).wants("/** a */"))
        assertFalse(plan(CommentChoice.JAVADOC).wants("// a"))
        assertFalse(plan(CommentChoice.EMPTY_JAVADOC).wants("// a"))
    }

    @Test
    fun `the line choice takes line comments and nothing else`() {
        val line = plan(CommentChoice.LINE)
        assertTrue(line.wants("// a"))
        assertTrue(line.wants("//no space"))
        assertFalse("a doc comment is not a line comment", line.wants("/** a */"))
        assertFalse("nor is a block comment", line.wants("/* a */"))
    }

    @Test
    fun `both takes either form`() {
        val both = plan(CommentChoice.JAVADOC_AND_LINE)
        assertTrue(both.wants("// a"))
        assertTrue(both.wants("/** a */"))
        assertFalse(both.wants("/* a */"))
    }

    @Test
    fun `a comment_id marker is spared unless it is asked for by name`() {
        val sparing = plan(CommentChoice.LINE)
        assertFalse(sparing.wants("// comment_id: 42"))
        assertEquals("and it is counted, so the summary can say so", 1, sparing.markersKept)

        val taking = plan(CommentChoice.LINE, includeMarkers = true)
        assertTrue(taking.wants("// comment_id: 42"))
        assertEquals(0, taking.markersKept)
    }

    @Test
    fun `both spares markers too`() {
        assertFalse(plan(CommentChoice.JAVADOC_AND_LINE).wants("// comment_id: 42"))
    }

    @Test
    fun `a trailing comment takes the space that separated it from the code`() {
        assertEquals("val a = 1", cutTrailing("val a = 1 // why\n", "// why").trimEnd('\n'))
        assertEquals("val a = 1", cutTrailing("val a = 1\t// why", "// why"))
        assertEquals("val a = 1", cutTrailing("val a = 1   /** why */", "/** why */"))
    }

    @Test
    fun `a line comment with the line to itself takes the line`() {
        val source = """
            val a = 1
            // explaining b
            val b = 2
        """.trimIndent()
        assertEquals(
            """
            val a = 1
            val b = 2
            """.trimIndent(),
            cutTrailing(source, "// explaining b"),
        )
    }

    @Test
    fun `the newline survives a trailing comment, so the next line does not join it`() {
        assertEquals("val a = 1\nval b = 2", cutTrailing("val a = 1 // why\nval b = 2", "// why"))
    }
}
