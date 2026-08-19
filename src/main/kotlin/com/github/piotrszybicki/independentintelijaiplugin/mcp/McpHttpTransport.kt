package com.github.piotrszybicki.independentintelijaiplugin.mcp

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import java.io.BufferedReader
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

class McpHttpTransport(private val config: McpServerConfig) : McpTransport {

    companion object {
        private val LOG = Logger.getInstance(McpHttpTransport::class.java)

        private val client: HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    private val gson = Gson()
    private val url = config.url.orEmpty()

    @Volatile
    private var sessionId: String? = null

    @Volatile
    var protocolVersion: String? = null

    override fun request(message: JsonObject, timeoutMillis: Long): JsonObject {
        val id = message.get("id")?.asInt ?: throw McpException("internal: request without an id")
        val response = post(message, timeoutMillis)

        if (response.statusCode() !in 200..299) {
            val body = runCatching { response.body().reader(StandardCharsets.UTF_8).readText() }.getOrDefault("")
            response.body().close()
            throw McpException("HTTP ${response.statusCode()} from $url${body.take(500).let { if (it.isBlank()) "" else ": $it" }}")
        }

        response.headers().firstValue("mcp-session-id").ifPresent { sessionId = it }

        val contentType = response.headers().firstValue("content-type").orElse("").lowercase()
        return response.body().use { stream ->
            if (contentType.contains("text/event-stream")) {
                readEventStream(stream.bufferedReader(StandardCharsets.UTF_8), id)
            } else {
                val body = stream.reader(StandardCharsets.UTF_8).readText()
                val parsed = runCatching { JsonParser.parseString(body) }.getOrNull()
                parsed?.takeIf { it.isJsonObject }?.asJsonObject
                    ?: throw McpException("$url answered with something that is not a JSON-RPC message: ${body.take(500)}")
            }
        }
    }

    override fun notify(message: JsonObject) {
        val response = runCatching { post(message, config.timeoutSeconds * 1000L) }.getOrElse {
            LOG.info("MCP notification to $url failed: ${it.message}")
            return
        }
        response.body().close()
    }

    override fun close() {
    }


    private fun post(message: JsonObject, timeoutMillis: Long): HttpResponse<InputStream> {
        val builder = HttpRequest.newBuilder()
            .uri(runCatching { URI.create(url) }.getOrElse { throw McpException("not a usable URL: $url") })
            .timeout(Duration.ofMillis(timeoutMillis))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(message)))

        sessionId?.let { builder.header("Mcp-Session-Id", it) }
        protocolVersion?.let { builder.header("MCP-Protocol-Version", it) }
        config.headers.forEach { (name, value) -> builder.header(name, value) }

        return try {
            client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw McpException("interrupted while waiting for $url")
        } catch (e: Exception) {
            throw McpException("could not reach $url: ${e.message}", e)
        }
    }

    private fun readEventStream(reader: BufferedReader, id: Int): JsonObject {
        val data = StringBuilder()

        fun matched(): JsonObject? {
            val payload = data.toString()
            data.setLength(0)
            if (payload.isBlank()) return null
            val message = runCatching { JsonParser.parseString(payload) }.getOrNull()
                ?.takeIf { it.isJsonObject }?.asJsonObject
                ?: return null
            val answered = runCatching { message.get("id")?.asInt }.getOrNull()
            return message.takeIf { answered == id }
        }

        for (line in reader.lineSequence()) {
            when {
                line.isEmpty() -> matched()?.let { return it }
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").removePrefix(" "))
                }
                else -> Unit
            }
        }
        matched()?.let { return it }

        throw McpException("$url ended its response stream without answering request $id")
    }
}
