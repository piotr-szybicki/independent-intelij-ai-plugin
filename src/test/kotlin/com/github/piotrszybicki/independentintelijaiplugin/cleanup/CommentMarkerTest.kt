package com.github.piotrszybicki.independentintelijaiplugin.cleanup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommentMarkerTest {

    @Test
    fun `writes the form the sweep and the model both use`() {
        assertEquals("// comment_id: 42", CommentMarker.of(42))
    }

    @Test
    fun `reads back what it wrote`() {
        for (id in listOf(1L, 42L, 9_999_999L)) {
            assertEquals(id, CommentMarker.idIn(CommentMarker.of(id)))
        }
    }

    @Test
    fun `tolerates the spacing a person would type`() {
        assertEquals(42L, CommentMarker.idIn("//comment_id:42"))
        assertEquals(42L, CommentMarker.idIn("//   comment_id:   42   "))
        assertEquals(42L, CommentMarker.idIn("  // comment_id: 42  "))
    }

    @Test
    fun `an ordinary comment is not a marker`() {
        assertNull(CommentMarker.idIn("// a normal comment"))
        assertNull("prose about markers is not one", CommentMarker.idIn("// see comment_id: 42 above"))
        assertNull("nor is a marker with something after it", CommentMarker.idIn("// comment_id: 42 and more"))
        assertNull("nor one with no number", CommentMarker.idIn("// comment_id:"))
    }

    @Test
    fun `a doc comment is never a marker, however it is written`() {
        assertNull(CommentMarker.idIn("/** comment_id: 42 */"))
        assertNull(CommentMarker.idIn("/* comment_id: 42 */"))
    }
}
