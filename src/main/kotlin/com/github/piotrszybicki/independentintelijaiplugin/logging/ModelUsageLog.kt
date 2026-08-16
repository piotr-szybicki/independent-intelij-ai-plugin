package com.github.piotrszybicki.independentintelijaiplugin.logging

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One CSV row per request, next to the traffic log, for charting what a conversation costs.
 *
 * Separate from [ModelTrafficLog] because the two are read in different ways: the traffic log is
 * read by eye when a request misbehaves, and this is read by a spreadsheet. Mixing them would mean
 * grepping token counts out of megabytes of JSON bodies, which is the reason this file exists.
 *
 * Deliberately not rotated, unlike the traffic log. A rotation would drop the header and cut the
 * series in half part-way through, and the file cannot grow the way the traffic one does: a row is
 * around a hundred bytes, so a hundred thousand requests is ten megabytes.
 *
 * ### What the columns mean
 *
 * The three input columns are disjoint and sum to `total_input_tokens`: `input_tokens` is what was
 * billed at full price, and what came from the cache is counted in its own column rather than in
 * that one. Both wire protocols are normalised to this shape before they get here -- the OpenAI
 * ones report the cached count *inside* their input total, and `OpenAiProtocol` subtracts it back
 * out. Without that, adding the columns would count every cached token twice.
 *
 * The three identity columns are what turn a surprising row into something that can be looked into:
 * `conversation_id` and `request_id` name the directory [ModelExchangeLog] wrote the two bodies to,
 * and `response_id` is the provider's own id for the message, which is what a support ticket about
 * a bad response has to quote.
 */
object ModelUsageLog {

    private const val HEADER = "timestamp,conversation_id,request_id,response_id,protocol,model," +
        "input_tokens,cache_write_tokens,cache_read_tokens,total_input_tokens,output_tokens,cache_hit_rate"

    /** Beside the traffic log, so "Collect Logs and Diagnostic Data" picks up both. */
    val currentFile: Path =
        PathManager.getLogDir().resolve("independent-ai-plugin").resolve("model-usage.csv")

    private val fallback = Logger.getInstance(ModelUsageLog::class.java)

    private val timestamp = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    /**
     * Set once the file has failed, so a directory that cannot be written does not mean a warning
     * per request for the rest of the session. Usage rows are worth having, not worth a turn.
     */
    private var broken = false

    /**
     * Appends one row. Synchronized because the agent runs on a pooled thread and a turn can have
     * several requests in flight across chats.
     */
    @Synchronized
    fun record(
        conversationId: String,
        requestId: String,
        responseId: String,
        protocol: String,
        model: String,
        inputTokens: Int,
        cacheWriteTokens: Int,
        cacheReadTokens: Int,
        outputTokens: Int,
    ) {
        if (broken) return

        val total = inputTokens + cacheWriteTokens + cacheReadTokens
        // Blank rather than zero when nothing was read at all: a rate of 0.0000 reads as "the cache
        // missed", and a request with no input to speak of did not have a cache to miss.
        val hitRate =
            if (total > 0) "%.4f".format(Locale.ROOT, cacheReadTokens.toDouble() / total) else ""

        val row = listOf(
            timestamp.format(Instant.now()),
            escape(conversationId),
            escape(requestId),
            escape(responseId),
            escape(protocol),
            escape(model),
            inputTokens.toString(),
            cacheWriteTokens.toString(),
            cacheReadTokens.toString(),
            total.toString(),
            outputTokens.toString(),
            hitRate,
        ).joinToString(",")

        runCatching {
            retireOutdatedFile()
            val fresh = Files.notExists(currentFile)
            if (fresh) {
                Files.createDirectories(currentFile.parent)
                // The breadcrumb goes in idea.log, the same as the traffic log's.
                fallback.info("Model usage stats: $currentFile")
            }
            Files.writeString(
                currentFile,
                if (fresh) "$HEADER\n$row\n" else "$row\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }.onFailure {
            broken = true
            fallback.warn("Could not write $currentFile; usage rows will not be recorded", it)
        }
    }

    /**
     * Set once the header has been checked, since it cannot change under a running IDE and reading
     * the first line of the file before every row would be a waste.
     */
    private var headerChecked = false

    /**
     * Renames a file whose header is not the current one out of the way, so the next row starts a
     * file with the right columns.
     *
     * The alternative is appending wider rows under a narrower header, which does not fail anywhere
     * -- it just quietly shifts every value in the new rows one or three columns to the left of what
     * the header claims, and a spreadsheet reads the mixture without complaint. The old rows are
     * renamed rather than deleted: they are the history of what the conversations cost, and this is
     * the one place it was written down.
     */
    private fun retireOutdatedFile() {
        if (headerChecked) return
        headerChecked = true
        if (Files.notExists(currentFile)) return

        val header = Files.newBufferedReader(currentFile).use { it.readLine() }
        if (header == HEADER) return

        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.now())
        val retired = currentFile.resolveSibling("model-usage-$stamp.csv")
        Files.move(currentFile, retired)
        fallback.info("Usage columns changed; the previous rows are in $retired")
    }

    /**
     * A model name is user-typed and a deployment name can hold anything, so the two text columns
     * are quoted when they need it rather than trusted to be comma-free.
     */
    private fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
}
