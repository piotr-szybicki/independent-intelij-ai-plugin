package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.github.piotrszybicki.independentintelijaiplugin.agents.AgentReturn
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTurn
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.ChatMessage
import com.github.piotrszybicki.independentintelijaiplugin.history.ChatStore
import com.github.piotrszybicki.independentintelijaiplugin.history.StoredChat
import com.github.piotrszybicki.independentintelijaiplugin.settings.AgentConfiguration
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FileRepairConversationTest {
    @Test
    fun `repair loop uses isolated chat with full files and saves every exchange`() {
        val store = MemoryStore()
        val requests = mutableListOf<String>()
        val ids = mutableListOf<String>()
        val chat =
            FileRepairConversation(store, AgentConfiguration.DEFAULT, "parent", "file.kt", {}) { request, id ->
                requests += request
                ids += id
                response(if (requests.size == 1) "intermediate\nwhole file" else "fixed\nwhole file")
            }
        var file = "original\nwhole file"
        val status =
            FileRepairLoop(
                read = { file },
                analyse = {
                    if (file.startsWith(
                            "fixed",
                        )
                    ) {
                        "No problems at severity warning or above in file.kt."
                    } else {
                        "error"
                    }
                },
                repair = chat::repair,
                replace = { _, replacement -> file = replacement },
                isCancelled = { false },
            ).run()
        chat.finish(status)

        assertEquals(2, requests.size)
        assertTrue(requests[0].contains("original\\nwhole file"))
        assertTrue(requests[1].contains("intermediate\\nwhole file"))
        assertFalse(requests[1].contains("original\\nwhole file"))
        assertEquals(1, ids.distinct().size)
        assertNotEquals("parent", ids.first())
        val saved = store.saved.last()
        assertEquals(ids.first(), saved.id)
        assertEquals("parent", saved.agent?.parentChatId)
        assertEquals(5, saved.messages.size)
        assertEquals(5, saved.transcript.size)
        assertEquals("Problems were detected and fixed", saved.transcript.last().text)
        assertTrue(saved.conversationTools!!.overridden)
        assertTrue(saved.conversationTools.tools.isEmpty())
    }

    @Test
    fun `truncated response cannot become a replacement even if valid JSON`() {
        val chat =
            FileRepairConversation(MemoryStore(), AgentConfiguration.DEFAULT, "parent", "file.kt", {}) { _, _ ->
                response("partial file", "max_tokens")
            }
        assertThrows(IllegalStateException::class.java) { chat.repair("original", "error") }
    }

    @Test
    fun `oversized file fails without clipping or calling model`() {
        val chat =
            FileRepairConversation(
                MemoryStore(),
                AgentConfiguration.DEFAULT.copy(contextWindowTokens = 1),
                "parent",
                "file.kt",
                {},
            ) { _, _ -> error("must not call model") }
        assertThrows(IllegalStateException::class.java) { chat.repair("original", "error") }
    }

    private fun response(
        content: String,
        stopReason: String = "end_turn",
    ): AICodingAgentTurn =
        AICodingAgentTurn(
            ChatMessage.text("assistant", JsonObject().apply { addProperty("content", content) }.toString()).content,
            stopReason,
        )

    private class MemoryStore : ChatStore {
        val saved = mutableListOf<StoredChat>()

        override fun load(id: String): StoredChat? = saved.lastOrNull { it.id == id }

        override fun save(
            chat: StoredChat,
            active: Boolean,
        ) {
            assertFalse(active)
            saved += chat
        }

        override fun addReturn(
            chatId: String,
            returned: AgentReturn,
        ): Boolean = error("unused")

        override fun delete(id: String) = error("unused")
    }
}
