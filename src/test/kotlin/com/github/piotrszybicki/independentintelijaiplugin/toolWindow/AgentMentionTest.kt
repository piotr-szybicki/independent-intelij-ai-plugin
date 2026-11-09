package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentMentionTest {
    @Test
    fun `distinguishes bare mention from instructions`() {
        val names = listOf("github", "github-agent")
        assertEquals(AgentMention.Request("github-agent", ""), AgentMention.parse("@github-agent ", names))
        assertEquals(
            AgentMention.Request("github-agent", "Review this\nplease"),
            AgentMention.parse("@github-agent Review this\nplease", names),
        )
    }

    @Test
    fun `does not match partial names or mentions within a message`() {
        assertNull(AgentMention.parse("@github-agent-extra hello", listOf("github-agent")))
        assertNull(AgentMention.parse("Ask @github-agent hello", listOf("github-agent")))
    }
}
