package com.github.piotrszybicki.independentintelijaiplugin.aicodingagent

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Putting a hand-edited tool result back into the conversation.
 *
 * The failure worth pinning is the near miss: writing the text into the wrong block, or into every
 * block of a round that made three calls at once. Either sends the model a `git_diff` labelled as a
 * `find_in_files`, which is not something a reader of the transcript would ever catch -- the
 * transcript shows what the tools actually returned.
 */
class ToolResultsTest {

    private fun toolResult(id: String, content: String): JsonObject = JsonObject().apply {
        addProperty("type", "tool_result")
        addProperty("tool_use_id", id)
        addProperty("content", content)
    }

    private fun resultsMessage(vararg blocks: JsonObject) =
        ChatMessage("user", JsonArray().apply { blocks.forEach { add(it) } })

    private fun contentOf(message: ChatMessage, index: Int): String =
        message.content[index].asJsonObject.get("content").asString

    @Test
    fun `replaces the result of the call it was asked for and no other`() {
        val history = mutableListOf(
            ChatMessage.text("user", "have a look"),
            resultsMessage(toolResult("call_1", "huge"), toolResult("call_2", "small")),
        )

        assertTrue(ToolResults.replace(history, "call_1", "the trimmed part"))
        assertEquals("the trimmed part", contentOf(history[1], 0))
        assertEquals("small", contentOf(history[1], 1))
    }

    /** The history is what the next request is built from, so the edit has to be visible in it. */
    @Test
    fun `leaves the rest of the conversation as it was`() {
        val first = ChatMessage.text("user", "have a look")
        val history = mutableListOf(first, resultsMessage(toolResult("call_1", "huge")))

        ToolResults.replace(history, "call_1", "trimmed")

        assertEquals(2, history.size)
        assertSame(first, history[0])
    }

    @Test
    fun `reports the call being gone rather than putting the text somewhere else`() {
        val history = mutableListOf(
            ChatMessage.text("user", "have a look"),
            resultsMessage(toolResult("call_1", "huge")),
        )

        assertFalse(ToolResults.replace(history, "call_9", "trimmed"))
        assertEquals("huge", contentOf(history[1], 0))
    }

    @Test
    fun `takes the last call with that id, which is the round the turn stopped in`() {
        val history = mutableListOf(
            resultsMessage(toolResult("call_1", "old")),
            ChatMessage.text("user", "again please"),
            resultsMessage(toolResult("call_1", "new")),
        )

        ToolResults.replace(history, "call_1", "trimmed")

        assertEquals("old", contentOf(history[0], 0))
        assertEquals("trimmed", contentOf(history[2], 0))
    }
}
