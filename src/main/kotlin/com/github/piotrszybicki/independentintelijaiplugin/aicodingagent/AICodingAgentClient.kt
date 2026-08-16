package com.github.piotrszybicki.independentintelijaiplugin.aicodingagent

import com.github.piotrszybicki.independentintelijaiplugin.logging.ModelExchangeLog
import com.github.piotrszybicki.independentintelijaiplugin.logging.ModelTrafficLog
import com.github.piotrszybicki.independentintelijaiplugin.logging.ModelUsageLog
import com.github.piotrszybicki.independentintelijaiplugin.settings.WireProtocol
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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
data class AICodingAgentUsage(
    val input_tokens: Int = 0,
    val output_tokens: Int = 0,
    val cache_creation_input_tokens: Int = 0,
    val cache_read_input_tokens: Int = 0,
)

/**
 * What a conversation has spent, summed over every request made in it.
 *
 * Counted per request rather than per message the user sends: a single turn is as many requests as
 * it takes tool calls, and the whole history goes out with each one, so the totals climb a good deal
 * faster than the transcript suggests. That is most of the reason for showing them at all.
 */
data class SessionUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
    val cacheReadTokens: Int = 0,
    /** How many requests went into the totals. */
    val requests: Int = 0,
) {

    /**
     * Everything the requests read, cached or not.
     *
     * The three add rather than overlap: `input_tokens` is what was billed at full price, and what
     * came from the cache is reported separately and left out of it. Summing them is the only way to
     * get the figure that would have been charged without caching.
     */
    val totalInputTokens: Int get() = inputTokens + cacheWriteTokens + cacheReadTokens

    /** The share of input served from the cache, or null before anything has been read. */
    val cacheHitRate: Double?
        get() = totalInputTokens.takeIf { it > 0 }?.let { cacheReadTokens.toDouble() / it }

    val isEmpty: Boolean get() = requests == 0

    operator fun plus(usage: AICodingAgentUsage): SessionUsage = SessionUsage(
        inputTokens + usage.input_tokens,
        outputTokens + usage.output_tokens,
        cacheWriteTokens + usage.cache_creation_input_tokens,
        cacheReadTokens + usage.cache_read_input_tokens,
        requests + 1,
    )
}

data class AICodingAgentTurn(val content: JsonArray, val stopReason: String?, val usage: AICodingAgentUsage? = null)

class AICodingAgentApiException(message: String) : Exception(message)

private data class AICodingAgentRequest(
    val model: String,
    val max_tokens: Int,
    /**
     * Sent as a list of content blocks rather than a bare string, because a cache breakpoint is a
     * field on a block and a string has nowhere to put one.
     */
    val system: JsonArray?,
    val messages: List<ChatMessage>,
    val tools: List<ToolDefinition>?,
    /** Null when left to the provider; Gson drops the field rather than sending a null. */
    val thinking: JsonObject?,
    val output_config: JsonObject?,
)

private data class AICodingAgentResponse(
    val content: JsonArray?,
    val stop_reason: String?,
    val usage: AICodingAgentUsage?,
)

object AICodingAgentClient {

    /** The wire traffic goes to a file of its own rather than into idea.log -- see [ModelTrafficLog]. */
    private val LOG = ModelTrafficLog

    private val gson = Gson()
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    fun sendMessage(
        endpoint: AICodingAgentEndpoint,
        model: String,
        maxTokens: Int,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition> = emptyList(),
        system: String? = null,
        reasoning: ReasoningOptions = ReasoningOptions.PROVIDER_DEFAULT,
        /**
         * Which conversation this request belongs to, which is what files it under a directory of
         * its own in [ModelExchangeLog] and what its [ModelUsageLog] row is grouped by. Blank for a
         * caller with no conversation to name, which lands under `unassigned` rather than nowhere.
         */
        conversationId: String = "",
    ): AICodingAgentTurn {
        endpoint.validate()?.let { throw AICodingAgentApiException("Cannot send the request: $it") }

        // The repair runs before the translation, not instead of it: the invariant it restores is
        // one every provider enforces, and it is easier to reason about on the plugin's own shape
        // than on either of the wire ones.
        val repaired = withOrphanedToolUsesAnswered(messages)

        val requestBody = when (endpoint.protocol) {
            // Cache breakpoints go on only here, and so do `thinking` and `output_config`. All of
            // them are Anthropic's, and OpenAI's APIs reject an unknown field rather than ignoring
            // it -- so a request bound for one of them would fail outright.
            WireProtocol.ANTHROPIC_MESSAGES -> gson.toJson(
                AICodingAgentRequest(
                    model,
                    maxTokens,
                    systemBlocks(system),
                    withCacheBreakpoints(repaired),
                    tools.ifEmpty { null },
                    reasoning.thinkingJson(),
                    reasoning.outputConfigJson(),
                )
            )

            WireProtocol.OPENAI_CHAT_COMPLETIONS ->
                OpenAiProtocol.chatCompletionsRequest(model, maxTokens, system, repaired, tools).toString()

            WireProtocol.OPENAI_RESPONSES ->
                OpenAiProtocol.responsesRequest(
                    model, maxTokens, system, repaired, tools, reasoning.reasoningJson(),
                ).toString()
        }
        // Headers are deliberately not logged: one of them is the token.
        LOG.info("${endpoint.protocol.name} request -> ${endpoint.url}: $requestBody")
        // Minted before the request goes out, not after it comes back: it names the directory the
        // request body is written to, which has to happen whether or not there is ever a response.
        val requestId = ModelExchangeLog.newRequestId()
        ModelExchangeLog.recordRequest(
            conversationId, requestId, endpoint.protocol.name, endpoint.url, model, requestBody,
        )

        val request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint.url))
            .timeout(Duration.ofSeconds(60))
            .apply { endpoint.headers().forEach { (name, value) -> header(name, value) } }
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        val startedAt = System.currentTimeMillis()
        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            LOG.info("API request failed: ${e.message}")
            ModelExchangeLog.recordFailure(
                conversationId, requestId, System.currentTimeMillis() - startedAt, e.toString(),
            )
            throw AICodingAgentApiException("Could not reach ${endpoint.url}: ${e.message}")
        }

        LOG.info("${endpoint.protocol.name} response <- ${response.statusCode()}: ${response.body()}")

        val root = runCatching { JsonParser.parseString(response.body()) }
            .getOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject

        // Before the status check, so a rejected request is a directory holding both halves of what
        // happened rather than a request and a silence.
        val responseId = providerResponseId(root)
        ModelExchangeLog.recordResponse(
            conversationId,
            requestId,
            responseId,
            response.statusCode(),
            System.currentTimeMillis() - startedAt,
            response.body(),
        )

        if (response.statusCode() !in 200..299) {
            // Anthropic and OpenAI both nest the message under `error`, so one path covers the
            // providers -- but a gateway in front of either may return neither shape, so fall back
            // to the raw body rather than reporting nothing.
            throw AICodingAgentApiException(errorMessage(root) ?: "HTTP ${response.statusCode()}: ${response.body()}")
        }
        if (root == null) throw AICodingAgentApiException("Empty or unrecognised response from ${endpoint.url}")

        val turn = when (endpoint.protocol) {
            WireProtocol.ANTHROPIC_MESSAGES -> {
                val parsed = runCatching { gson.fromJson(root, AICodingAgentResponse::class.java) }.getOrNull()
                val content = parsed?.content
                    ?: throw AICodingAgentApiException("Empty or unrecognised response from ${endpoint.url}")
                AICodingAgentTurn(content, parsed.stop_reason, parsed.usage)
            }

            WireProtocol.OPENAI_CHAT_COMPLETIONS -> OpenAiProtocol.parseChatCompletions(root)
            WireProtocol.OPENAI_RESPONSES -> OpenAiProtocol.parseResponses(root)
        }

        turn.usage?.let {
            LOG.info(
                "usage: input=${it.input_tokens} output=${it.output_tokens} " +
                    "cache_write=${it.cache_creation_input_tokens} cache_read=${it.cache_read_input_tokens}",
            )
            ModelUsageLog.record(
                conversationId,
                requestId,
                responseId,
                endpoint.protocol.name,
                model,
                it.input_tokens,
                it.cache_creation_input_tokens,
                it.cache_read_input_tokens,
                it.output_tokens,
            )
        }
        return turn
    }

    /**
     * The provider's own id for the message, or blank when the body carried none.
     *
     * One field covers all three protocols: Anthropic's `msg_...`, chat completions' `chatcmpl_...`
     * and the responses API's `resp_...` are all at the top level under `id`. It is worth recording
     * because it is the only identifier the provider also has -- a request that came back wrong is
     * asked about by quoting this, and nothing else in the row means anything on their side.
     *
     * Blank rather than absent for an error body, which generally has no id at all.
     */
    private fun providerResponseId(root: JsonObject?): String =
        root?.get("id")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    /**
     * The human-readable half of an error body, whichever of the two shapes it arrived in.
     *
     * `error` is an object with a `message` on both providers, but some compatible servers put a
     * bare string there instead, and reporting `{"error":"model not found"}` as nothing at all is
     * the worst of the options.
     */
    private fun errorMessage(root: JsonObject?): String? {
        val error = root?.get("error") ?: return null
        if (error.isJsonPrimitive) return error.asString.takeIf { it.isNotBlank() }
        if (!error.isJsonObject) return null
        return error.asJsonObject.get("message")
            ?.takeIf { it.isJsonPrimitive }
            ?.asString
            ?.takeIf { it.isNotBlank() }
    }

    // --- repair -----------------------------------------------------------------------------------

    /**
     * Stands in for a tool call the conversation never recorded an answer for. Says what happened
     * rather than inventing a result, so the model retries the call instead of assuming it worked.
     */
    private const val ORPHANED_RESULT =
        "This tool call was interrupted and never ran. Nothing it would have done has happened. " +
            "Call it again if you still need it."

    /**
     * Answers any `tool_use` block the next message does not, so a history that lost a tool result
     * can still be sent.
     *
     * The API requires a `tool_result` for every `tool_use` in the message immediately after, and
     * rejects the whole request otherwise -- which makes a single missing result terminal rather
     * than awkward. It is in every message from then on, so every retry fails the same way and the
     * conversation cannot be continued at all, only abandoned.
     *
     * The agent loop is careful never to produce that, and a conversation only reaching the API is
     * not where the gap opens: it is a history saved to disk part-way through an iteration and read
     * back later, at which point the damage is permanent and in a file. Repairing here rather than
     * at the point of the loss covers whatever caused it, including the chats already saved.
     */
    internal fun withOrphanedToolUsesAnswered(messages: List<ChatMessage>): List<ChatMessage> {
        val repaired = mutableListOf<ChatMessage>()
        var i = 0
        while (i < messages.size) {
            val message = messages[i]
            repaired.add(message)

            val pending = toolUseIds(message)
            val next = messages.getOrNull(i + 1)
            // Only a user message can carry results; anything else leaves every id unanswered.
            val answered = if (next != null && next.role == "user") toolResultIds(next) else emptySet()
            val missing = pending - answered
            if (missing.isEmpty()) {
                i++
                continue
            }

            LOG.info("Answering ${missing.size} unanswered tool_use block(s) in message $i: $missing")
            val content = JsonArray()
            missing.forEach { id ->
                content.add(JsonObject().apply {
                    addProperty("type", "tool_result")
                    addProperty("tool_use_id", id)
                    addProperty("content", ORPHANED_RESULT)
                })
            }

            if (next != null && next.role == "user") {
                // Merge rather than insert: two user messages in a row would put the real results
                // one message too late, which is the same rejection again.
                next.content.forEach { content.add(it) }
                repaired.add(ChatMessage("user", content))
                i += 2
            } else {
                repaired.add(ChatMessage("user", content))
                i++
            }
        }
        return repaired
    }

    /** The ids of every `tool_use` block in [message], in the order they appear. */
    private fun toolUseIds(message: ChatMessage): Set<String> {
        if (message.role != "assistant") return emptySet()
        val ids = linkedSetOf<String>()
        for (block in message.content) {
            if (!block.isJsonObject) continue
            val obj = block.asJsonObject
            if (obj.get("type")?.asString != "tool_use") continue
            obj.get("id")?.asString?.takeIf { it.isNotEmpty() }?.let { ids.add(it) }
        }
        return ids
    }

    private fun toolResultIds(message: ChatMessage): Set<String> {
        val ids = mutableSetOf<String>()
        for (block in message.content) {
            if (!block.isJsonObject) continue
            val obj = block.asJsonObject
            if (obj.get("type")?.asString != "tool_result") continue
            obj.get("tool_use_id")?.asString?.let { ids.add(it) }
        }
        return ids
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

    /**
     * The block types that accept a breakpoint.
     *
     * A `thinking` block does not: its schema has no `cache_control` field, and marking one is
     * rejected outright with "Extra inputs are not permitted" -- taking the whole conversation down
     * with it, since the offending block stays in the history and every retry sends it again. The
     * model returns thinking blocks whenever thinking is on, which on the current models it is by
     * default, so an assistant turn ending in one is ordinary rather than a corner case.
     *
     * An allow list rather than a deny list on purpose. A type nobody anticipated then goes
     * unmarked, which costs a breakpoint; guessing the other way costs the request.
     */
    private val CACHEABLE_BLOCK_TYPES = setOf("text", "tool_use", "tool_result", "image", "document")

    /**
     * A breakpoint that keeps what it caches for an hour rather than the default five minutes.
     *
     * The gap it has to survive is the user reading an answer and editing code before sending the
     * next message, which is routinely longer than five minutes -- and an entry that expires in
     * that gap is re-read at full price, tool schemas and conversation alike. Writing the longer
     * one costs twice the base price instead of 1.25x, so it takes three requests to pay for itself
     * rather than two; a single turn answering a round of tool calls already sends more than that.
     *
     * On every breakpoint rather than only the system one, so a returning user reads back the
     * conversation as well as the prefix in front of it. The extra write is smaller than it sounds:
     * after the first request what is written is only the blocks appended since, and it is the
     * whole history before them that is read.
     */
    private fun ephemeral(): JsonObject = JsonObject().apply {
        addProperty("type", "ephemeral")
        addProperty("ttl", "1h")
    }

    /**
     * Wraps the system prompt in a single text block carrying a cache breakpoint.
     *
     * One breakpoint here covers the whole stable prefix: the request renders as tools, then system,
     * then messages, so marking the end of system caches the tool definitions along with it. That is
     * the bulk of every request -- some thirty tool schemas and a system prompt that does not change
     * for the life of the conversation -- and without this it is re-read at full price on every
     * iteration of every turn.
     *
     * Nothing is cached until the prefix passes the model's minimum, which differs per model and
     * is not ordered by how new it is -- between 512 and 4096 tokens across the current ones. Below
     * it the marker is simply ignored: no error, no cache, which is what the usage counters are for.
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

        // Not necessarily the last message: a turn can end on a thinking block, which cannot carry
        // the marker, so the newest markable message is what the tail breakpoint lands on.
        val tail = messages.indices.lastOrNull { markableBlock(messages[it]) >= 0 } ?: return messages
        val marked = mutableSetOf(tail)

        var blocks = 0
        for (i in tail downTo 0) {
            blocks += messages[i].content.size()
            // The tail is excluded because it already carries a breakpoint. Without that, a message
            // wide enough to satisfy the lookback on its own -- the user turn answering a round of
            // tool calls, which is several blocks at once -- ends the search on itself and leaves
            // the second breakpoint unplaced, in exactly the case it exists for.
            if (i < tail && blocks >= LOOKBACK_BLOCKS && markableBlock(messages[i]) >= 0) {
                marked.add(i)
                break
            }
        }

        return messages.mapIndexed { i, message -> if (i in marked) withBreakpoint(message) else message }
    }

    /**
     * The index of the last block in [message] that can carry a breakpoint, or -1 when it has none.
     *
     * The last one rather than the first, so that the cached span covers as much of the message as
     * the API allows.
     */
    private fun markableBlock(message: ChatMessage): Int {
        val content = message.content
        for (i in content.size() - 1 downTo 0) {
            val block = content[i]
            if (!block.isJsonObject) continue
            if (block.asJsonObject.get("type")?.asString in CACHEABLE_BLOCK_TYPES) return i
        }
        return -1
    }

    /** A copy of [message] whose last markable content block carries a breakpoint. */
    private fun withBreakpoint(message: ChatMessage): ChatMessage {
        val at = markableBlock(message)
        if (at < 0) return message

        val content = message.content
        val copy = JsonArray()
        for (i in 0 until content.size()) {
            copy.add(
                if (i == at) {
                    content[i].deepCopy().asJsonObject.apply { add("cache_control", ephemeral()) }
                } else {
                    content[i]
                },
            )
        }
        return ChatMessage(message.role, copy)
    }
}
