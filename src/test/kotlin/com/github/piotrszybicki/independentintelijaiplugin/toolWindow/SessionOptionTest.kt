package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.github.piotrszybicki.independentintelijaiplugin.settings.ConversationTools
import com.github.piotrszybicki.independentintelijaiplugin.tools.ToolCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionOptionTest {
    @Test
    fun `coding preset replaces tools without configuration and preserves skills`() {
        val current =
            ConversationTools(tools = listOf("git_log"), mcpTools = listOf("external"), skills = listOf("review"))
        val chosen = SessionOption.CODING_TOOLS.applyTo(current)

        assertTrue(chosen.overridden)
        assertEquals(20, chosen.tools.size)
        assertTrue(chosen.tools.containsAll(listOf("edit_file_lines", "run_shell_command", "analyse_and_fix")))
        assertTrue(chosen.tools.all { name -> ToolCatalog.entries.any { it.name == name } })
        assertTrue(chosen.mcpTools.isEmpty())
        assertEquals(current.skills, chosen.skills)
        assertEquals(chosen, SessionOption.CODING_TOOLS.applyTo(chosen))
    }

    @Test
    fun `recognizes the coding tools command`() {
        assertEquals(SessionOption.CODING_TOOLS, SessionOption.fromCommand("+coding-tools"))
    }

    @Test
    fun `ordinary messages and unknown options are not commands`() {
        assertNull(SessionOption.fromCommand("Please use +coding-tools"))
        assertNull(SessionOption.fromCommand("+unknown"))
        assertNull(SessionOption.fromCommand("a+b"))
    }
}
