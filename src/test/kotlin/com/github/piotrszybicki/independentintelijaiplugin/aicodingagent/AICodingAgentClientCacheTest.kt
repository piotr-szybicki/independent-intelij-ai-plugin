package com.github.piotrszybicki.independentintelijaiplugin.aicodingagent

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers where the cache breakpoints land.
 *
 * Both invariants here fail in ways nothing local would catch: too many breakpoints is a request the
 * API rejects, and a marker left on the live history is a leak that only shows up several turns
 * later, once the count has crept past the limit.
 */
class AICodingAgentClientCacheTest {

    companion object {
        /** The API's ceiling of four, minus the one the system prompt always carries. */
        private const val MAX_MESSAGE_BREAKPOINTS = 2
    }

    @Test
    fun `marks the last message`() {
        val messages = listOf(message("user", 1), message("assistant", 1), message("user", 1))

        val marked = AICodingAgentClient.withCacheBreakpoints(messages)

        assertEquals(1, marked.last().breakpoints())
    }

    @Test
    fun `leaves the caller's history untouched`() {
        val messages = listOf(message("user", 1), message("assistant", 20), message("user", 3))

        AICodingAgentClient.withCacheBreakpoints(messages)

        assertTrue(messages.all { it.breakpoints() == 0 })
    }

    @Test
    fun `never exceeds the breakpoint budget`() {
        // Long enough that every message is a candidate, and wide enough that the lookback is
        // satisfied several times over.
        val messages = (1..40).map { message(if (it % 2 == 0) "assistant" else "user", 4) }

        val marked = AICodingAgentClient.withCacheBreakpoints(messages)

        assertTrue(marked.sumOf { it.breakpoints() } <= MAX_MESSAGE_BREAKPOINTS)
    }

    @Test
    fun `marks a second point once the conversation is deep enough`() {
        val messages = (1..10).map { message("user", 4) }

        val marked = AICodingAgentClient.withCacheBreakpoints(messages)

        assertEquals(MAX_MESSAGE_BREAKPOINTS, marked.sumOf { it.breakpoints() })
    }

    /** One message cannot be two breakpoints, however many blocks it holds. */
    @Test
    fun `marks once when a single message covers the lookback`() {
        val messages = listOf(message("user", 50))

        val marked = AICodingAgentClient.withCacheBreakpoints(messages)

        assertEquals(1, marked.single().breakpoints())
    }

    /**
     * A wide last message is the reason the second breakpoint exists, not a reason to skip it: the
     * turn that answers several tool calls at once is what puts the tail out of the previous
     * request's reach.
     */
    @Test
    fun `still marks an older message when the last one covers the lookback alone`() {
        val messages = listOf(message("user", 2), message("assistant", 2), message("user", 20))

        val marked = AICodingAgentClient.withCacheBreakpoints(messages)

        assertEquals(MAX_MESSAGE_BREAKPOINTS, marked.sumOf { it.breakpoints() })
        assertEquals(1, marked[1].breakpoints())
    }

    @Test
    fun `marks the final block, not an earlier one`() {
        val messages = listOf(message("user", 3))

        val content = AICodingAgentClient.withCacheBreakpoints(messages).single().content

        assertFalse(content[0].asJsonObject.has("cache_control"))
        assertFalse(content[1].asJsonObject.has("cache_control"))
        assertTrue(content[2].asJsonObject.has("cache_control"))
    }

    /**
     * The default five minutes is shorter than the gap between one message and the next, so a
     * breakpoint that lost its TTL would expire unnoticed and cost the whole prefix every turn.
     */
    @Test
    fun `caches for an hour rather than the default`() {
        val marked = AICodingAgentClient.withCacheBreakpoints(listOf(message("user", 1)))

        val control = marked.single().content[0].asJsonObject.getAsJsonObject("cache_control")
        assertEquals("ephemeral", control.get("type").asString)
        assertEquals("1h", control.get("ttl").asString)
    }

    @Test
    fun `passes an empty history through`() {
        assertTrue(AICodingAgentClient.withCacheBreakpoints(emptyList()).isEmpty())
    }

    /**
     * A thinking block has no `cache_control` field, and marking one is rejected with "Extra inputs
     * are not permitted" -- which fails the whole request, not just the caching.
     */
    @Test
    fun `never marks a thinking block`() {
        val messages = listOf(
            message("user", 1),
            ChatMessage("assistant", blocks("thinking" to 1, "text" to 1, "thinking" to 1)),
        )

        val marked = AICodingAgentClient.withCacheBreakpoints(messages)

        val content = marked.last().content
        assertFalse(content[0].asJsonObject.has("cache_control"))
        assertTrue(content[1].asJsonObject.has("cache_control"))
        assertFalse(content[2].asJsonObject.has("cache_control"))
    }

    /** An interrupted turn can leave an assistant message that is nothing but thinking. */
    @Test
    fun `falls back to an older message when the last one cannot be marked`() {
        val messages = listOf(
            message("user", 1),
            ChatMessage("assistant", blocks("thinking" to 1)),
        )

        val marked = AICodingAgentClient.withCacheBreakpoints(messages)

        assertEquals(0, marked.last().breakpoints())
        assertEquals(1, marked.first().breakpoints())
    }

    @Test
    fun `marks nothing when no block can carry a breakpoint`() {
        val messages = listOf(ChatMessage("assistant", blocks("thinking" to 1, "redacted_thinking" to 1)))

        val marked = AICodingAgentClient.withCacheBreakpoints(messages)

        assertEquals(0, marked.sumOf { it.breakpoints() })
    }

    // --- orphaned tool calls ----------------------------------------------------------------------

    @Test
    fun `answers a tool_use the following message left unanswered`() {
        val messages = listOf(
            ChatMessage.text("user", "go"),
            ChatMessage("assistant", toolUse("toolu_1")),
        )

        val repaired = AICodingAgentClient.withOrphanedToolUsesAnswered(messages)

        assertEquals(3, repaired.size)
        assertEquals("user", repaired.last().role)
        assertEquals(setOf("toolu_1"), resultIds(repaired.last()))
    }

    /** The results must be in the message straight after, so a partial answer is merged into it. */
    @Test
    fun `merges into the existing results rather than inserting a message`() {
        val messages = listOf(
            ChatMessage("assistant", toolUse("toolu_1", "toolu_2")),
            ChatMessage("user", toolResult("toolu_2")),
        )

        val repaired = AICodingAgentClient.withOrphanedToolUsesAnswered(messages)

        assertEquals(2, repaired.size)
        assertEquals(setOf("toolu_1", "toolu_2"), resultIds(repaired[1]))
    }

    @Test
    fun `leaves a well-formed conversation alone`() {
        val messages = listOf(
            ChatMessage.text("user", "go"),
            ChatMessage("assistant", toolUse("toolu_1")),
            ChatMessage("user", toolResult("toolu_1")),
        )

        val repaired = AICodingAgentClient.withOrphanedToolUsesAnswered(messages)

        assertEquals(messages, repaired)
    }

    /** A user turn typed after an interrupted tool call is not an answer to it. */
    @Test
    fun `does not count an unrelated user message as an answer`() {
        val messages = listOf(
            ChatMessage("assistant", toolUse("toolu_1")),
            ChatMessage.text("user", "never mind, do something else"),
        )

        val repaired = AICodingAgentClient.withOrphanedToolUsesAnswered(messages)

        assertEquals(2, repaired.size)
        assertEquals(setOf("toolu_1"), resultIds(repaired[1]))
        // The typed text survives, after the result that has to come first.
        assertEquals(2, repaired[1].content.size())
    }

    private fun toolUse(vararg ids: String): JsonArray {
        val content = JsonArray()
        ids.forEach { id ->
            content.add(JsonObject().apply {
                addProperty("type", "tool_use")
                addProperty("id", id)
                addProperty("name", "read_project_file")
            })
        }
        return content
    }

    private fun toolResult(vararg ids: String): JsonArray {
        val content = JsonArray()
        ids.forEach { id ->
            content.add(JsonObject().apply {
                addProperty("type", "tool_result")
                addProperty("tool_use_id", id)
                addProperty("content", "ok")
            })
        }
        return content
    }

    private fun resultIds(message: ChatMessage): Set<String> =
        message.content
            .filter { it.isJsonObject && it.asJsonObject.get("type")?.asString == "tool_result" }
            .map { it.asJsonObject.get("tool_use_id").asString }
            .toSet()

    private fun blocks(vararg types: Pair<String, Int>): JsonArray {
        val content = JsonArray()
        for ((type, count) in types) {
            repeat(count) {
                content.add(JsonObject().apply { addProperty("type", type) })
            }
        }
        return content
    }

    private fun message(role: String, blocks: Int): ChatMessage {
        val content = JsonArray()
        repeat(blocks) { i ->
            content.add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", "block $i")
            })
        }
        return ChatMessage(role, content)
    }

    private fun ChatMessage.breakpoints(): Int =
        content.count { it.isJsonObject && it.asJsonObject.has("cache_control") }
}
