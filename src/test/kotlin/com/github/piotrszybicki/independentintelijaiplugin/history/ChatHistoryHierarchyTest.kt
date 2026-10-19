package com.github.piotrszybicki.independentintelijaiplugin.history

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatHistoryHierarchyTest {
    @Test
    fun `places agent conversations beneath the chat that started them`() {
        val chats =
            listOf(
                summary("child", updatedAt = 30, parentChatId = "parent"),
                summary("other", updatedAt = 20),
                summary("parent", updatedAt = 10),
            )

        val entries = ChatHistoryHierarchy.arrange(chats)

        assertEquals(listOf("parent", "child", "other"), entries.map { it.chat.id })
        assertEquals(listOf(0, 1, 0), entries.map { it.depth })
    }

    @Test
    fun `supports nested agent handoffs`() {
        val chats =
            listOf(
                summary("grandchild", updatedAt = 30, parentChatId = "child"),
                summary("child", updatedAt = 20, parentChatId = "parent"),
                summary("parent", updatedAt = 10),
            )

        val entries = ChatHistoryHierarchy.arrange(chats)

        assertEquals(listOf("parent", "child", "grandchild"), entries.map { it.chat.id })
        assertEquals(listOf(0, 1, 2), entries.map { it.depth })
    }

    @Test
    fun `reports one based conversation levels`() {
        val chats =
            listOf(
                summary("grandchild", updatedAt = 30, parentChatId = "child"),
                summary("child", updatedAt = 20, parentChatId = "parent"),
                summary("parent", updatedAt = 10),
            )

        assertEquals(1, ChatHistoryHierarchy.levelOf("parent", chats))
        assertEquals(2, ChatHistoryHierarchy.levelOf("child", chats))
        assertEquals(3, ChatHistoryHierarchy.levelOf("grandchild", chats))
    }

    @Test
    fun `cannot determine a level for an orphan or cycle`() {
        val chats =
            listOf(
                summary("orphan", updatedAt = 30, parentChatId = "missing"),
                summary("cycle-a", updatedAt = 20, parentChatId = "cycle-b"),
                summary("cycle-b", updatedAt = 10, parentChatId = "cycle-a"),
            )

        assertEquals(null, ChatHistoryHierarchy.levelOf("orphan", chats))
        assertEquals(null, ChatHistoryHierarchy.levelOf("cycle-a", chats))
    }

    @Test
    fun `keeps an agent visible when its parent is no longer saved`() {
        val entries = ChatHistoryHierarchy.arrange(listOf(summary("orphan", updatedAt = 10, parentChatId = "missing")))

        assertEquals(listOf("orphan"), entries.map { it.chat.id })
        assertEquals(listOf(0), entries.map { it.depth })
    }

    private fun summary(
        id: String,
        updatedAt: Long,
        parentChatId: String? = null,
    ) = ChatSummary(
        id = id,
        title = id,
        updatedAt = updatedAt,
        agentName = parentChatId?.let { "agent" },
        parentChatId = parentChatId,
    )
}
