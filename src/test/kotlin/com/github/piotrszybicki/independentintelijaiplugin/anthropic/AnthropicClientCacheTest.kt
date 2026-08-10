package com.github.piotrszybicki.independentintelijaiplugin.anthropic

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
class AnthropicClientCacheTest {

    companion object {
        /** The API's ceiling of four, minus the one the system prompt always carries. */
        private const val MAX_MESSAGE_BREAKPOINTS = 2
    }

    @Test
    fun `marks the last message`() {
        val messages = listOf(message("user", 1), message("assistant", 1), message("user", 1))

        val marked = AnthropicClient.withCacheBreakpoints(messages)

        assertEquals(1, marked.last().breakpoints())
    }

    @Test
    fun `leaves the caller's history untouched`() {
        val messages = listOf(message("user", 1), message("assistant", 20), message("user", 3))

        AnthropicClient.withCacheBreakpoints(messages)

        assertTrue(messages.all { it.breakpoints() == 0 })
    }

    @Test
    fun `never exceeds the breakpoint budget`() {
        // Long enough that every message is a candidate, and wide enough that the lookback is
        // satisfied several times over.
        val messages = (1..40).map { message(if (it % 2 == 0) "assistant" else "user", 4) }

        val marked = AnthropicClient.withCacheBreakpoints(messages)

        assertTrue(marked.sumOf { it.breakpoints() } <= MAX_MESSAGE_BREAKPOINTS)
    }

    @Test
    fun `marks a second point once the conversation is deep enough`() {
        val messages = (1..10).map { message("user", 4) }

        val marked = AnthropicClient.withCacheBreakpoints(messages)

        assertEquals(MAX_MESSAGE_BREAKPOINTS, marked.sumOf { it.breakpoints() })
    }

    /** One message cannot be two breakpoints, however many blocks it holds. */
    @Test
    fun `marks once when a single message covers the lookback`() {
        val messages = listOf(message("user", 50))

        val marked = AnthropicClient.withCacheBreakpoints(messages)

        assertEquals(1, marked.single().breakpoints())
    }

    @Test
    fun `marks the final block, not an earlier one`() {
        val messages = listOf(message("user", 3))

        val content = AnthropicClient.withCacheBreakpoints(messages).single().content

        assertFalse(content[0].asJsonObject.has("cache_control"))
        assertFalse(content[1].asJsonObject.has("cache_control"))
        assertTrue(content[2].asJsonObject.has("cache_control"))
    }

    @Test
    fun `passes an empty history through`() {
        assertTrue(AnthropicClient.withCacheBreakpoints(emptyList()).isEmpty())
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
