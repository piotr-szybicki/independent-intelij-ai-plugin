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

data class AnthropicTurn(val content: JsonArray, val stopReason: String?)

class AnthropicApiException(message: String) : Exception(message)

private data class AnthropicRequest(
    val model: String,
    val max_tokens: Int,
    val system: String?,
    val messages: List<ChatMessage>,
    val tools: List<ToolDefinition>?,
)

private data class AnthropicErrorBody(val type: String?, val message: String?)

private data class AnthropicResponse(
    val content: JsonArray?,
    val stop_reason: String?,
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
            AnthropicRequest(model, maxTokens, system, messages, tools.ifEmpty { null })
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
        return AnthropicTurn(content, parsed.stop_reason)
    }
}
