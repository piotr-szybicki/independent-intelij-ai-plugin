package com.github.piotrszybicki.independentintelijaiplugin.aicodingagent

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AICodingAgentClientCacheTest {

    companion object {
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

    @Test
    fun `marks once when a single message covers the lookback`() {
        val messages = listOf(message("user", 50))

        val marked = AICodingAgentClient.withCacheBreakpoints(messages)

        assertEquals(1, marked.single().breakpoints())
    }

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

    @Test
    fun `keeps the tail for the default five minutes`() {
        val marked = AICodingAgentClient.withCacheBreakpoints(listOf(message("user", 1)))

        val control = marked.single().content[0].asJsonObject.getAsJsonObject("cache_control")
        assertEquals("ephemeral", control.get("type").asString)
        assertEquals("5m", control.get("ttl").asString)
    }

    @Test
    fun `never lets a ttl grow from one breakpoint to the next`() {
        val messages = (1..10).map { message("user", 4) }

        val marked = AICodingAgentClient.withCacheBreakpoints(messages)

        val tail = marked.indexOfLast { it.breakpoints() > 0 }
        val lookback = marked.indexOfFirst { it.breakpoints() > 0 }
        assertTrue("expected two distinct breakpoints", lookback in 0 until tail)
        assertEquals("1h", marked[lookback].ttl())
        assertEquals("5m", marked[tail].ttl())

        val ttls = listOf("1h") + marked.filter { it.breakpoints() > 0 }.map { it.ttl() }
        ttls.zipWithNext().forEach { (earlier, later) ->
            assertTrue("ttl '$later' must not come after ttl '$earlier'", rank(later) <= rank(earlier))
        }
    }

    private fun rank(ttl: String): Int = when (ttl) {
        "1h" -> 2
        "5m" -> 1
        else -> throw IllegalArgumentException("unrecognised ttl '$ttl'")
    }

    @Test
    fun `passes an empty history through`() {
        assertTrue(AICodingAgentClient.withCacheBreakpoints(emptyList()).isEmpty())
    }

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

    @Test
    fun `does not count an unrelated user message as an answer`() {
        val messages = listOf(
            ChatMessage("assistant", toolUse("toolu_1")),
            ChatMessage.text("user", "never mind, do something else"),
        )

        val repaired = AICodingAgentClient.withOrphanedToolUsesAnswered(messages)

        assertEquals(2, repaired.size)
        assertEquals(setOf("toolu_1"), resultIds(repaired[1]))
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

    private fun ChatMessage.ttl(): String =
        content.first { it.isJsonObject && it.asJsonObject.has("cache_control") }
            .asJsonObject.getAsJsonObject("cache_control").get("ttl").asString
}
