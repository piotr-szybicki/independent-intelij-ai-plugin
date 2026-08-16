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

/**
 * Every request and response written out whole, one pretty-printed JSON file each, filed by
 * conversation.
 *
 * ```
 * exchanges/<conversation-id>/<request-id>/request.json
 *                                         /response.json
 * ```
 *
 * The third of the three logs, and the one to reach for when a single request has to be looked at
 * closely. [ModelTrafficLog] holds the same bodies but as one line of a rolling text file, which is
 * fine for "what went out just now" and useless for "what did the history look like on the fourth
 * tool call of that chat yesterday" -- the bodies run to hundreds of kilobytes, they are interleaved
 * with every other chat's, and they are on one line. Here each one is a file that an editor will
 * fold, a diff will compare against the previous request, and `jq` will read.
 *
 * The `request_id` naming each directory is the same one [ModelUsageLog] puts in its row, so a
 * spreadsheet row that looks wrong leads back to the bodies that produced it.
 *
 * Deliberately never pruned or rotated. That is a real cost -- unlike the traffic log this grows
 * without bound, roughly the size of the conversation per request, and an afternoon of agentic work
 * is tens of megabytes -- but a log that deletes the exchange being investigated is worse than a
 * large one, and the directory is the user's to empty.
 */
object ModelExchangeLog {

    /**
     * Beside the other two logs, so "Collect Logs and Diagnostic Data" picks the tree up along with
     * them.
     */
    val root: Path =
        PathManager.getLogDir().resolve("independent-ai-plugin").resolve("exchanges")

    private val fallback = Logger.getInstance(ModelExchangeLog::class.java)

    private val gson = GsonBuilder()
        // Bodies hold prose, and prose holds angle brackets and ampersands. Escaped to `<` they
        // are still valid JSON and no longer readable, which defeats the point of the file.
        .disableHtmlEscaping()
        .setPrettyPrinting()
        .create()

    private val timestamp = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        .withZone(ZoneId.systemDefault())

    /** Sorts the directories chronologically when listed by name, which is how they are read. */
    private val idStamp = DateTimeFormatter
        .ofPattern("yyyyMMdd-HHmmss-SSS")
        .withZone(ZoneId.systemDefault())

    /**
     * Breaks the tie between two requests that start in the same millisecond, which a turn answering
     * a round of tool calls in parallel would otherwise do.
     */
    private val sequence = AtomicLong()

    /** Stands in for a request made outside any chat, so its files still land somewhere. */
    private const val UNASSIGNED = "unassigned"

    /**
     * Set once a write has failed, so a log directory that cannot be written costs one warning for
     * the session rather than two per request. Same bargain as [ModelUsageLog]'s.
     */
    @Volatile
    private var broken = false

    /**
     * The id for one request/response pair: the directory its two files go in, and the `request_id`
     * column tying [ModelUsageLog]'s row to them.
     *
     * Minted by the caller before the request goes out rather than derived from anything on the
     * wire, because the response may never arrive and the request still has to be findable.
     */
    fun newRequestId(): String = "${idStamp.format(Instant.now())}-${"%05d".format(sequence.incrementAndGet())}"

    /**
     * Writes `request.json`. [body] is the serialised request, embedded as JSON rather than as a
     * string so the file is one document instead of a document wrapped around an escaped one.
     *
     * Headers are left out on purpose, the same as in [ModelTrafficLog]: one of them is the token.
     */
    fun recordRequest(
        conversationId: String,
        requestId: String,
        protocol: String,
        url: String,
        model: String,
        body: String,
    ) {
        write(conversationId, requestId, REQUEST_FILE) {
            JsonObject().apply {
                addProperty("request_id", requestId)
                addProperty("conversation_id", conversationId.ifBlank { UNASSIGNED })
                addProperty("timestamp", timestamp.format(Instant.now()))
                addProperty("protocol", protocol)
                addProperty("url", url)
                addProperty("model", model)
                add("body", parsed(body))
            }
        }
    }

    /**
     * Writes `response.json` beside the request it answers. [responseId] is the provider's own id
     * for the message, blank when the body carried none.
     */
    fun recordResponse(
        conversationId: String,
        requestId: String,
        responseId: String,
        statusCode: Int,
        durationMillis: Long,
        body: String,
    ) {
        write(conversationId, requestId, RESPONSE_FILE) {
            JsonObject().apply {
                addProperty("request_id", requestId)
                addProperty("response_id", responseId)
                addProperty("conversation_id", conversationId.ifBlank { UNASSIGNED })
                addProperty("timestamp", timestamp.format(Instant.now()))
                addProperty("status_code", statusCode)
                addProperty("duration_ms", durationMillis)
                add("body", parsed(body))
            }
        }
    }

    /**
     * Writes `response.json` for a request that never got one -- a timeout, a refused connection, a
     * TLS failure.
     *
     * Written rather than skipped so the pair is never half a story: a directory holding a request
     * and nothing else reads as "the log broke", and this is the case where the bodies are most
     * worth having.
     */
    fun recordFailure(
        conversationId: String,
        requestId: String,
        durationMillis: Long,
        error: String,
    ) {
        write(conversationId, requestId, RESPONSE_FILE) {
            JsonObject().apply {
                addProperty("request_id", requestId)
                addProperty("conversation_id", conversationId.ifBlank { UNASSIGNED })
                addProperty("timestamp", timestamp.format(Instant.now()))
                addProperty("duration_ms", durationMillis)
                addProperty("error", error)
            }
        }
    }

    // --- storage ------------------------------------------------------------------------------------

    private const val REQUEST_FILE = "request.json"
    private const val RESPONSE_FILE = "response.json"

    /**
     * The document is built inside the `runCatching` rather than passed in, so a body that cannot be
     * serialised is caught here along with the disk failures. Nothing about logging a request is
     * worth taking a turn down for.
     */
    private fun write(conversationId: String, requestId: String, name: String, document: () -> JsonObject) {
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

    /**
     * One path segment made out of [value].
     *
     * A chat id is a UUID and a request id is minted above, so neither should need this -- but both
     * end up in a path, and a directory traversal out of the log tree is not the kind of thing to
     * leave resting on an assumption about who calls it.
     */
    private fun segment(value: String, fallbackName: String): String {
        val cleaned = value.trim().map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
            .joinToString("")
            .take(120)
        return cleaned.ifEmpty { fallbackName }
    }

    /**
     * [body] as JSON, or as a string when it is not JSON at all -- which an error body from a
     * gateway in front of the provider often is not, and an HTML error page is exactly when the file
     * needs to still be written.
     */
    private fun parsed(body: String): JsonElement =
        runCatching { JsonParser.parseString(body) }.getOrNull()?.takeIf { !it.isJsonNull }
            ?: JsonPrimitive(body)
}
