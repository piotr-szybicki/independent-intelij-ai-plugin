package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.JTextPane

class MentionPopupSupportTest {
    @Test
    fun `accepting a mention suppresses only the immediately following submit`() {
        val input = JTextPane()

        ComposerSubmitSuppression.suppressNextSubmit(input)

        assertTrue(ComposerSubmitSuppression.consumeSuppressedSubmit(input))
        assertFalse(ComposerSubmitSuppression.consumeSuppressedSubmit(input))
    }
}
