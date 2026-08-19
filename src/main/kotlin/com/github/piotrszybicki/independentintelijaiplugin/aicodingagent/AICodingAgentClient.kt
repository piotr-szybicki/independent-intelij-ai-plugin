package com.github.piotrszybicki.independentintelijaiplugin.aicodingagent

import com.github.piotrszybicki.independentintelijaiplugin.logging.ModelExchangeLog
import com.github.piotrszybicki.independentintelijaiplugin.logging.ModelTrafficLog
import com.github.piotrszybicki.independentintelijaiplugin.logging.ModelUsageDatabase
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

data class AICodingAgentUsage(
    val input_tokens: Int = 0,
    val output_tokens: Int = 0,
    val cache_creation_input_tokens: Int = 0,
    val cache_read_input_tokens: Int = 0,
) {

    val promptTokens: Int
        get() = input_tokens + cache_creation_input_tokens + cache_read_input_tokens
}

data class SessionUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
    val cacheReadTokens: Int = 0,
    val requests: Int = 0,
) {

    val totalInputTokens: Int get() = inputTokens + cacheWriteTokens + cacheReadTokens

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

data class AICodingAgentTurn(
    val content: JsonArray,
    val stopReason: String?,
    val usage: AICodingAgentUsage? = null,
    val requestId: String = "",
)

class AICodingAgentApiException(message: String) : Exception(message)

private data class AICodingAgentRequest(
    val model: String,
    val max_tokens: Int,
    val system: JsonArray?,
    val messages: List<ChatMessage>,
    val tools: List<ToolDefinition>?,
    val thinking: JsonObject?,
    val output_config: JsonObject?,
)

private data class AICodingAgentResponse(
    val content: JsonArray?,
    val stop_reason: String?,
    val usage: AICodingAgentUsage?,
)

object AICodingAgentClient {

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
        conversationId: String = "",
    ): AICodingAgentTurn {
        endpoint.validate()?.let { throw AICodingAgentApiException("Cannot send the request: $it") }

        val repaired = withOrphanedToolUsesAnswered(messages)

        val requestBody = when (endpoint.protocol) {
            WireProtocol.ANTHROPIC_MESSAGES -> gson.toJson(
                AICodingAgentRequest(
                    model,
                    maxTokens,
                    systemBlocks(system),
                    withCacheBreakpoints(repaired),
                    tools.ifEmpty { null },
                    reasoning.thinkingJson(model, maxTokens),
                    reasoning.outputConfigJson(model),
                )
            )

            WireProtocol.OPENAI_CHAT_COMPLETIONS ->
                OpenAiProtocol.chatCompletionsRequest(
                    model, maxTokens, system, repaired, tools, cacheKey = conversationId,
                ).toString()

            WireProtocol.OPENAI_RESPONSES ->
                OpenAiProtocol.responsesRequest(
                    model, maxTokens, system, repaired, tools,
                    cacheKey = conversationId,
                    reasoning = reasoning.reasoningJson(),
                ).toString()
        }
        LOG.info("${endpoint.protocol.name} request -> ${endpoint.url}: $requestBody")
        val requestId = ModelExchangeLog.newRequestId()
        ModelExchangeLog.recordRequest(conversationId, requestId, requestBody)
        ModelUsageDatabase.recordRequest(
            conversationId, requestId, endpoint.protocol.name, endpoint.url, model, requestBody,
        )

        val request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint.url))
            .timeout(Duration.ofSeconds(endpoint.requestTimeoutSeconds.toLong()))
            .apply { endpoint.headers().forEach { (name, value) -> header(name, value) } }
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        val startedAt = System.currentTimeMillis()
        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            LOG.info("API request failed: ${e.message}")
            val elapsed = System.currentTimeMillis() - startedAt
            ModelExchangeLog.recordFailure(conversationId, requestId, e.toString())
            ModelUsageDatabase.recordFailure(conversationId, requestId, elapsed, e.toString())
            throw AICodingAgentApiException("Could not reach ${endpoint.url}: ${e.message}")
        }

        LOG.info("${endpoint.protocol.name} response <- ${response.statusCode()}: ${response.body()}")

        val root = runCatching { JsonParser.parseString(response.body()) }
            .getOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject

        val responseId = providerResponseId(root)
        ModelExchangeLog.recordResponse(conversationId, requestId, response.body())
        ModelUsageDatabase.recordResponse(
            conversationId,
            requestId,
            responseId,
            response.statusCode(),
            System.currentTimeMillis() - startedAt,
            response.body(),
        )

        if (response.statusCode() !in 200..299) {
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
            ModelUsageDatabase.recordUsage(
                conversationId,
                requestId,
                model,
                it.input_tokens,
                it.cache_creation_input_tokens,
                it.cache_read_input_tokens,
                it.output_tokens,
            )
        }
        return turn.copy(requestId = requestId)
    }

    private fun providerResponseId(root: JsonObject?): String =
        root?.get("id")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    private fun errorMessage(root: JsonObject?): String? {
        val error = root?.get("error") ?: return null
        if (error.isJsonPrimitive) return error.asString.takeIf { it.isNotBlank() }
        if (!error.isJsonObject) return null
        return error.asJsonObject.get("message")
            ?.takeIf { it.isJsonPrimitive }
            ?.asString
            ?.takeIf { it.isNotBlank() }
    }


    private const val ORPHANED_RESULT =
        "This tool call was interrupted and never ran. Nothing it would have done has happened. " +
            "Call it again if you still need it."

    internal fun withOrphanedToolUsesAnswered(messages: List<ChatMessage>): List<ChatMessage> {
        val repaired = mutableListOf<ChatMessage>()
        var i = 0
        while (i < messages.size) {
            val message = messages[i]
            repaired.add(message)

            val pending = toolUseIds(message)
            val next = messages.getOrNull(i + 1)
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


    private const val LOOKBACK_BLOCKS = 15

    private val CACHEABLE_BLOCK_TYPES = setOf("text", "tool_use", "tool_result", "image", "document")

    private const val ONE_HOUR = "1h"
    private const val FIVE_MINUTES = "5m"

    private const val SYSTEM_TTL = ONE_HOUR
    private const val LOOKBACK_TTL = ONE_HOUR
    private const val TAIL_TTL = FIVE_MINUTES

    private fun ephemeral(ttl: String): JsonObject = JsonObject().apply {
        addProperty("type", "ephemeral")
        addProperty("ttl", ttl)
    }

    private fun systemBlocks(system: String?): JsonArray? {
        if (system.isNullOrEmpty()) return null
        val block = JsonObject().apply {
            addProperty("type", "text")
            addProperty("text", system)
            add("cache_control", ephemeral(SYSTEM_TTL))
        }
        return JsonArray().apply { add(block) }
    }

    internal fun withCacheBreakpoints(messages: List<ChatMessage>): List<ChatMessage> {
        if (messages.isEmpty()) return messages

        val tail = messages.indices.lastOrNull { markableBlock(messages[it]) >= 0 } ?: return messages
        val marked = mutableMapOf(tail to TAIL_TTL)

        var blocks = 0
        for (i in tail downTo 0) {
            blocks += messages[i].content.size()
            if (i < tail && blocks >= LOOKBACK_BLOCKS && markableBlock(messages[i]) >= 0) {
                marked[i] = LOOKBACK_TTL
                break
            }
        }

        return messages.mapIndexed { i, message ->
            marked[i]?.let { withBreakpoint(message, it) } ?: message
        }
    }

    private fun markableBlock(message: ChatMessage): Int {
        val content = message.content
        for (i in content.size() - 1 downTo 0) {
            val block = content[i]
            if (!block.isJsonObject) continue
            if (block.asJsonObject.get("type")?.asString in CACHEABLE_BLOCK_TYPES) return i
        }
        return -1
    }

    private fun withBreakpoint(message: ChatMessage, ttl: String): ChatMessage {
        val at = markableBlock(message)
        if (at < 0) return message

        val content = message.content
        val copy = JsonArray()
        for (i in 0 until content.size()) {
            copy.add(
                if (i == at) {
                    content[i].deepCopy().asJsonObject.apply { add("cache_control", ephemeral(ttl)) }
                } else {
                    content[i]
                },
            )
        }
        return ChatMessage(message.role, copy)
    }
}
