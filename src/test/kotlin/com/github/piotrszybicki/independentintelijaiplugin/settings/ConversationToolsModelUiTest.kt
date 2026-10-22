package com.github.piotrszybicki.independentintelijaiplugin.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationToolsModelUiTest {
    @Test
    fun `inheritance and custom selection expose stable semantic state`() {
        val inherited = ConversationTools(false, listOf("read"), emptyList(), emptyList())
        val custom = inherited.withSkill("review")

        assertTrue(custom.isCustom)
        assertEquals(setOf("review"), custom.activeSkills)
        assertTrue(custom.describe().isNotBlank())
        assertTrue(custom.buttonText().isNotBlank())
    }
}
