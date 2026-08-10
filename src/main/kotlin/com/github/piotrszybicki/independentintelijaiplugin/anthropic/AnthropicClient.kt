package com.github.piotrszybicki.independentintelijaiplugin.anthropic

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class ChatMessage(val role: String, val content: JsonArray) {
    companion object {
        fun text(role: String, text: String): ChatMessage {
            val block = JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", text)
            }
            return ChatMessage(role, JsonArray().apply { add(block) })
        }
    }
}

data class ToolDefinition(val name: String, val description: String, val input_schema: JsonObject)

/**
 * What the request cost, as the API reported it.
 *
 * The two cache fields are the only way to find out whether prompt caching is actually working:
 * a prefix that fails to match reports a write every turn and never a read, and nothing else about
 * the response looks any different.
 */
data class AnthropicUsage(
    val input_tokens: Int = 0,
    val output_tokens: Int = 0,
    val cache_creation_input_tokens: Int = 0,
    val cache_read_input_tokens: Int = 0,
)

data class AnthropicTurn(val content: JsonArray, val stopReason: String?, val usage: AnthropicUsage? = null)

class AnthropicApiException(message: String) : Exception(message)

private data class AnthropicRequest(
    val model: String,
    val max_tokens: Int,
    /**
     * Sent as a list of content blocks rather than a bare string, because a cache breakpoint is a
     * field on a block and a string has nowhere to put one.
     */
    val system: JsonArray?,
    val messages: List<ChatMessage>,
    val tools: List<ToolDefinition>?,
)

private data class AnthropicErrorBody(val type: String?, val message: String?)

private data class AnthropicResponse(
    val content: JsonArray?,
    val stop_reason: String?,
    val usage: AnthropicUsage?,
    val error: AnthropicErrorBody?,
)

object AnthropicClient {

    private val LOG = Logger.getInstance(AnthropicClient::class.java)

    private val gson = Gson()
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    fun sendMessage(
        endpoint: AnthropicEndpoint,
        model: String,
        maxTokens: Int,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition> = emptyList(),
        system: String? = null,
    ): AnthropicTurn {
        endpoint.validate()?.let { throw AnthropicApiException("Cannot send the request: $it") }

        val requestBody = gson.toJson(
            AnthropicRequest(
                model,
                maxTokens,
                systemBlocks(system),
                withCacheBreakpoints(messages),
                tools.ifEmpty { null },
            )
        )
        // Headers are deliberately not logged: one of them is the token.
        LOG.info("Anthropic API request -> ${endpoint.url}: $requestBody")

        val request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint.url))
            .timeout(Duration.ofSeconds(60))
            .apply { endpoint.headers().forEach { (name, value) -> header(name, value) } }
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            LOG.info("Anthropic API request failed: ${e.message}")
            throw AnthropicApiException("Could not reach ${endpoint.url}: ${e.message}")
        }

        LOG.info("Anthropic API response <- ${response.statusCode()}: ${response.body()}")

        val parsed = runCatching { gson.fromJson(response.body(), AnthropicResponse::class.java) }.getOrNull()

        if (response.statusCode() !in 200..299) {
            // A gateway that is not speaking the Messages API will not return the error shape
            // either, so fall back to the raw body rather than reporting nothing.
            throw AnthropicApiException(parsed?.error?.message ?: "HTTP ${response.statusCode()}: ${response.body()}")
        }

        val content = parsed?.content
            ?: throw AnthropicApiException("Empty or unrecognised response from ${endpoint.url}")

        parsed.usage?.let {
            LOG.info(
                "Anthropic usage: input=${it.input_tokens} output=${it.output_tokens} " +
                    "cache_write=${it.cache_creation_input_tokens} cache_read=${it.cache_read_input_tokens}",
            )
        }
        return AnthropicTurn(content, parsed.stop_reason, parsed.usage)
    }

    // --- prompt caching ---------------------------------------------------------------------------

    /**
     * How far back the second message breakpoint is placed, in content blocks.
     *
     * A breakpoint only looks back a bounded number of blocks for an existing cache entry, so in a
     * turn that adds many of them -- an agentic loop answering several tool calls at once -- a single
     * breakpoint at the very end can end up out of range of the previous request's and silently miss.
     * Marking a second, older point keeps one within reach.
     */
    private const val LOOKBACK_BLOCKS = 15

    private fun ephemeral(): JsonObject = JsonObject().apply { addProperty("type", "ephemeral") }

    /**
     * Wraps the system prompt in a single text block carrying a cache breakpoint.
     *
     * One breakpoint here covers the whole stable prefix: the request renders as tools, then system,
     * then messages, so marking the end of system caches the tool definitions along with it. That is
     * the bulk of every request -- some thirty tool schemas and a system prompt that does not change
     * for the life of the conversation -- and without this it is re-read at full price on every
     * iteration of every turn.
     *
     * Nothing is cached until the prefix passes the model's minimum, which is around a thousand
     * tokens and differs per model. Below it the marker is simply ignored: no error, no cache.
     */
    private fun systemBlocks(system: String?): JsonArray? {
        if (system.isNullOrEmpty()) return null
        val block = JsonObject().apply {
            addProperty("type", "text")
            addProperty("text", system)
            add("cache_control", ephemeral())
        }
        return JsonArray().apply { add(block) }
    }

    /**
     * Returns [messages] with cache breakpoints on the last block of at most two of them.
     *
     * The conversation is resent in full on every request, so marking its tail means each request
     * reads back everything the previous one already paid for. The marker moves forward as the
     * history grows, which is what the API expects -- it is metadata rather than content, so moving
     * it does not invalidate what was cached under it.
     *
     * Marked messages are copied rather than annotated in place: [messages] is the live history and
     * an accumulating marker on every turn would run past the four-breakpoint limit within a few
     * exchanges. Unmarked entries are passed through by reference, since they are only serialised.
     *
     * Marks at most two, which with the one on the system prompt leaves the request inside the limit.
     * Internal rather than private so that bound can be asserted in a test -- exceeding it is a
     * request the API rejects outright, and nothing before the round trip would catch it.
     */
    internal fun withCacheBreakpoints(messages: List<ChatMessage>): List<ChatMessage> {
        if (messages.isEmpty()) return messages

        val marked = mutableSetOf(messages.lastIndex)
        var blocks = 0
        for (i in messages.lastIndex downTo 0) {
            blocks += messages[i].content.size()
            if (blocks >= LOOKBACK_BLOCKS) {
                marked.add(i)
                break
            }
        }

        return messages.mapIndexed { i, message -> if (i in marked) withBreakpoint(message) else message }
    }

    /** A copy of [message] whose final content block carries a breakpoint. */
    private fun withBreakpoint(message: ChatMessage): ChatMessage {
        val content = message.content
        val last = content.lastOrNull()
        // Only an object can carry the field, and an empty message has nothing to mark.
        if (last == null || !last.isJsonObject) return message

        val copy = JsonArray()
        for (i in 0 until content.size() - 1) copy.add(content[i])
        copy.add(last.deepCopy().asJsonObject.apply { add("cache_control", ephemeral()) })
        return ChatMessage(message.role, copy)
    }
}
