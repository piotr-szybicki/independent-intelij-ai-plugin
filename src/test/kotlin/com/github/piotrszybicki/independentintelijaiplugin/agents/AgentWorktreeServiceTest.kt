package com.github.piotrszybicki.independentintelijaiplugin.agents

import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.ChatMessage
import com.github.piotrszybicki.independentintelijaiplugin.history.StoredChat
import com.github.piotrszybicki.independentintelijaiplugin.history.StoredRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AgentWorktreeServiceTest {
    @Test
    fun `matches equivalent normalized paths`() {
        val root = Files.createTempDirectory("worktree-test")
        try {
            val target = root.resolve("target")
            Files.createDirectories(target)
            assertTrue(AgentWorktreeService.samePath(target.resolve("."), target))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `does not match different paths`() {
        val root = Files.createTempDirectory("worktree-test")
        try {
            assertFalse(AgentWorktreeService.samePath(root.resolve("one"), root.resolve("two")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `collects current and all available ancestor worktrees`() {
        val chats =
            listOf(
                chat("current", "parent", "/work/current"),
                chat("parent", "grandparent", "/work/parent"),
                chat("grandparent", null, "/work/grandparent"),
            )

        assertEquals(
            setOf("current", "parent", "grandparent")
                .map {
                    java.nio.file.Path
                        .of("/work/$it")
                        .normalize()
                        .toString()
                }.toSet(),
            AgentWorktreeService.ancestorWorktreePaths("current", chats).map { it.toString() }.toSet(),
        )
    }

    @Test
    fun `stops safely at missing parent and cycles`() {
        val chats =
            listOf(
                chat("current", "missing", "/work/current"),
                chat("cycle-a", "cycle-b", "/work/a"),
                chat("cycle-b", "cycle-a", "/work/b"),
            )

        assertEquals(
            setOf(
                java.nio.file.Path
                    .of("/work/current"),
            ),
            AgentWorktreeService.ancestorWorktreePaths("current", chats),
        )
        assertEquals(
            setOf(
                java.nio.file.Path
                    .of("/work/a"),
                java.nio.file.Path
                    .of("/work/b"),
            ),
            AgentWorktreeService.ancestorWorktreePaths("cycle-a", chats),
        )
    }

    @Test
    fun `ignores missing worktree metadata and duplicate paths`() {
        val chats =
            listOf(
                chat("current", "parent", null),
                chat("parent", null, "/work/shared"),
            )

        assertEquals(
            setOf(
                java.nio.file.Path
                    .of("/work/shared"),
            ),
            AgentWorktreeService.ancestorWorktreePaths("current", chats),
        )
    }

    private fun chat(
        id: String,
        parent: String?,
        worktree: String?,
    ): StoredChat =
        StoredChat(
            id = id,
            title = id,
            createdAt = 0,
            updatedAt = 0,
            messages = listOf(ChatMessage.text("user", "message")),
            transcript = listOf(StoredRow(StoredRow.USER, text = "message")),
            agent = AgentSession("agent", parent, null, worktree, null),
        )
}
