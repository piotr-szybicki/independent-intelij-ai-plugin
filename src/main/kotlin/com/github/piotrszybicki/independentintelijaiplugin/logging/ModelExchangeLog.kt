package com.github.piotrszybicki.independentintelijaiplugin.logging

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

object ModelExchangeLog {

    val root: Path =
        PathManager.getLogDir().resolve("independent-ai-plugin").resolve("exchanges")

    private val fallback = Logger.getInstance(ModelExchangeLog::class.java)

    private val gson = GsonBuilder()
        // Bodies hold prose, and prose holds angle brackets and ampersands. Escaped to `<` they
        // are still valid JSON and no longer readable, which defeats the point of the file.
        .disableHtmlEscaping()
        .setPrettyPrinting()
        .create()

    private val idStamp = DateTimeFormatter
        .ofPattern("yyyyMMdd-HHmmss-SSS")
        .withZone(ZoneId.systemDefault())

    private val sequence = AtomicLong()

    private const val UNASSIGNED = "unassigned"

    @Volatile
    private var broken = false

    fun newRequestId(): String = "${idStamp.format(Instant.now())}-${"%05d".format(sequence.incrementAndGet())}"

    fun recordRequest(conversationId: String, requestId: String, body: String) {
        write(conversationId, requestId, REQUEST_FILE) { parsed(body) }
    }

    fun recordResponse(conversationId: String, requestId: String, body: String) {
        write(conversationId, requestId, RESPONSE_FILE) { parsed(body) }
    }

    fun recordFailure(conversationId: String, requestId: String, error: String) {
        write(conversationId, requestId, RESPONSE_FILE) {
            JsonObject().apply { addProperty("error", error) }
        }
    }

    // --- storage ------------------------------------------------------------------------------------

    private const val REQUEST_FILE = "request.json"
    private const val RESPONSE_FILE = "response.json"

    private fun write(conversationId: String, requestId: String, name: String, document: () -> JsonElement) {
        if (broken) return
        runCatching {
            val directory = root.resolve(segment(conversationId, UNASSIGNED)).resolve(segment(requestId, "request"))
            val fresh = Files.notExists(root)
            Files.createDirectories(directory)
            // The breadcrumb goes in idea.log, the same as the other two logs'.
            if (fresh) fallback.info("Model request/response files: $root")
            Files.writeString(directory.resolve(name), gson.toJson(document()))
        }.onFailure {
            broken = true
            fallback.warn("Could not write under $root; request/response files will not be recorded", it)
        }
    }

    private fun segment(value: String, fallbackName: String): String {
        val cleaned = value.trim().map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
            .joinToString("")
            .take(120)
        return cleaned.ifEmpty { fallbackName }
    }

    private fun parsed(body: String): JsonElement =
        runCatching { JsonParser.parseString(body) }.getOrNull()?.takeIf { !it.isJsonNull }
            ?: JsonPrimitive(body)
}
