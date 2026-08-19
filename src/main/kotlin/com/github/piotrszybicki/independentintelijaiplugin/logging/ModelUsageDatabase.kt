package com.github.piotrszybicki.independentintelijaiplugin.logging

import com.github.piotrszybicki.independentintelijaiplugin.settings.AgentConfiguration
import com.github.piotrszybicki.independentintelijaiplugin.settings.AgentConfigurations
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.concurrency.AppExecutorUtil
import com.mysql.cj.jdbc.Driver
import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

object ModelUsageDatabase {

    private val fallback = Logger.getInstance(ModelUsageDatabase::class.java)

    const val TABLE = "model_requests"

    const val TOOL_TABLE = "model_tool_calls"

    private const val RETRY_INTERVAL_MILLIS = 30_000L

    private val TIMEOUTS = mapOf("connectTimeout" to "5000", "socketTimeout" to "10000")

    private val executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("AI plugin usage log", 1)

    private const val UNASSIGNED = "unassigned"

    private var connection: Connection? = null

    private var connectedTo: String = ""

    private var nextAttemptAt = 0L

    private var dropped = 0

    // --- writes -------------------------------------------------------------------------------------

    fun recordRequest(
        conversationId: String,
        requestId: String,
        protocol: String,
        url: String,
        model: String,
        body: String,
    ) {
        // Converted here rather than in the task, so the parse happens once and a body that cannot be
        // parsed does not fail the write on a background thread.
        val document = asJson(body)
        submit(
            """
            INSERT INTO $TABLE (request_id, conversation_id, started_at, protocol, url, model, request_body)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                conversation_id = ?, protocol = ?, url = ?, model = ?, request_body = ?
            """.trimIndent(),
        ) { startedAt ->
            setString(1, requestId)
            setString(2, conversationId.ifBlank { UNASSIGNED })
            setTimestamp(3, startedAt)
            setString(4, protocol)
            setString(5, url)
            setString(6, model)
            setString(7, document)
            setString(8, conversationId.ifBlank { UNASSIGNED })
            setString(9, protocol)
            setString(10, url)
            setString(11, model)
            setString(12, document)
        }
    }

    fun recordResponse(
        conversationId: String,
        requestId: String,
        responseId: String,
        statusCode: Int,
        durationMillis: Long,
        body: String,
    ) {
        val document = asJson(body)
        submit(
            """
            INSERT INTO $TABLE (
                request_id, conversation_id, started_at, response_id, status_code, duration_ms, response_body
            )
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                response_id = ?, status_code = ?, duration_ms = ?, response_body = ?
            """.trimIndent(),
        ) { startedAt ->
            setString(1, requestId)
            setString(2, conversationId.ifBlank { UNASSIGNED })
            setTimestamp(3, startedAt)
            // NULL rather than blank when the body carried no id, which an error body generally does
            // not -- so `WHERE response_id IS NULL` finds them.
            setString(4, responseId.takeIf { it.isNotBlank() })
            setInt(5, statusCode)
            setLong(6, durationMillis)
            setString(7, document)
            setString(8, responseId.takeIf { it.isNotBlank() })
            setInt(9, statusCode)
            setLong(10, durationMillis)
            setString(11, document)
        }
    }

    fun recordFailure(
        conversationId: String,
        requestId: String,
        durationMillis: Long,
        error: String,
    ) {
        submit(
            """
            INSERT INTO $TABLE (request_id, conversation_id, started_at, duration_ms, error)
            VALUES (?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE duration_ms = ?, error = ?
            """.trimIndent(),
        ) { startedAt ->
            setString(1, requestId)
            setString(2, conversationId.ifBlank { UNASSIGNED })
            setTimestamp(3, startedAt)
            setLong(4, durationMillis)
            setString(5, error)
            setLong(6, durationMillis)
            setString(7, error)
        }
    }

    fun recordUsage(
        conversationId: String,
        requestId: String,
        model: String,
        inputTokens: Int,
        cacheWriteTokens: Int,
        cacheReadTokens: Int,
        outputTokens: Int,
    ) {
        val total = inputTokens + cacheWriteTokens + cacheReadTokens
        // NULL rather than 0.0 when nothing was read at all: a rate of zero reads as "the cache
        // missed", and a request with no input to speak of did not have a cache to miss. NULL is
        // also what AVG() skips, which is the whole reason the distinction is worth keeping.
        val hitRate = if (total > 0) cacheReadTokens.toDouble() / total else null

        // Priced here rather than on the server, so the column holds exactly the number the chat
        // window drew under the reply -- same function, same counts. See [ModelPricing].
        val cost = ModelPricing.costUsd(model, inputTokens, cacheWriteTokens, cacheReadTokens, outputTokens)
        if (cost == null) reportUnpriced(model)

        submit(
            """
            INSERT INTO $TABLE (
                request_id, conversation_id, started_at,
                input_tokens, cache_write_tokens, cache_read_tokens,
                total_input_tokens, output_tokens, cache_hit_rate, cost_usd
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                input_tokens = ?, cache_write_tokens = ?, cache_read_tokens = ?,
                total_input_tokens = ?, output_tokens = ?, cache_hit_rate = ?, cost_usd = ?
            """.trimIndent(),
        ) { startedAt ->
            setString(1, requestId)
            setString(2, conversationId.ifBlank { UNASSIGNED })
            setTimestamp(3, startedAt)
            setInt(4, inputTokens)
            setInt(5, cacheWriteTokens)
            setInt(6, cacheReadTokens)
            setInt(7, total)
            setInt(8, outputTokens)
            setDoubleOrNull(9, hitRate)
            setDecimalOrNull(10, cost)
            setInt(11, inputTokens)
            setInt(12, cacheWriteTokens)
            setInt(13, cacheReadTokens)
            setInt(14, total)
            setInt(15, outputTokens)
            setDoubleOrNull(16, hitRate)
            setDecimalOrNull(17, cost)
        }
    }

    private val unpricedWarned = ConcurrentHashMap.newKeySet<String>()

    private fun reportUnpriced(model: String) {
        if (!unpricedWarned.add(model)) return
        fallback.info(
            "No price is configured for '$model', so its rows record no cost and no figure is shown " +
                "in the chat. Priced models: ${ModelPricing.pricedModels().joinToString(", ")}",
        )
    }

    // --- tool calls ---------------------------------------------------------------------------------

    fun recordToolCall(
        conversationId: String,
        requestId: String,
        ordinal: Int,
        toolUseId: String,
        toolName: String,
        arguments: String,
        result: String,
        outcome: String,
        argumentTokens: Int,
        resultTokens: Int,
        durationMillis: Long,
    ) {
        // A request id is what the row hangs off; without one there is nothing to attach it to and
        // the insert would only fail on the foreign key.
        if (requestId.isBlank()) return

        val document = asJson(arguments)
        val stored = truncated(result)
        submit(
            """
            INSERT INTO $TOOL_TABLE (
                request_id, conversation_id, ordinal, tool_use_id, tool_name, outcome,
                finished_at, duration_ms, arguments, result, argument_tokens, result_tokens
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                tool_use_id = ?, tool_name = ?, outcome = ?, finished_at = ?, duration_ms = ?,
                arguments = ?, result = ?, argument_tokens = ?, result_tokens = ?
            """.trimIndent(),
        ) { finishedAt ->
            setString(1, requestId)
            setString(2, conversationId.ifBlank { UNASSIGNED })
            setInt(3, ordinal)
            setString(4, toolUseId.takeIf { it.isNotBlank() })
            setString(5, toolName)
            setString(6, outcome)
            setTimestamp(7, finishedAt)
            setLong(8, durationMillis)
            setString(9, document)
            setString(10, stored)
            setInt(11, argumentTokens)
            setInt(12, resultTokens)
            setString(13, toolUseId.takeIf { it.isNotBlank() })
            setString(14, toolName)
            setString(15, outcome)
            setTimestamp(16, finishedAt)
            setLong(17, durationMillis)
            setString(18, document)
            setString(19, stored)
            setInt(20, argumentTokens)
            setInt(21, resultTokens)
        }
    }

    private const val MAX_RESULT_CHARS = 1_000_000

    private fun truncated(result: String): String =
        if (result.length <= MAX_RESULT_CHARS) {
            result
        } else {
            result.take(MAX_RESULT_CHARS) +
                "\n\n[... truncated: ${result.length - MAX_RESULT_CHARS} more characters were not recorded]"
        }

    // --- plumbing -----------------------------------------------------------------------------------

    private fun submit(sql: String, bind: PreparedStatement.(Timestamp) -> Unit) {
        val target = configuredUrl()
        if (target.isEmpty()) return
        val startedAt = Timestamp.from(Instant.now())
        executor.execute { runWrite(target, sql, startedAt, bind) }
    }

    @Synchronized
    private fun runWrite(target: String, sql: String, startedAt: Timestamp, bind: PreparedStatement.(Timestamp) -> Unit) {
        if (System.currentTimeMillis() < nextAttemptAt) {
            dropped++
            return
        }
        // Retried once on a fresh connection rather than failed outright: the common failure here is
        // a connection the server closed while the IDE sat idle -- `wait_timeout` is eight hours by
        // default and a restarted container is instant -- which looks like an error on first use and
        // works immediately afterwards.
        runCatching { execute(target, sql, startedAt, bind) }
            .recoverCatching {
                closeQuietly()
                execute(target, sql, startedAt, bind)
            }
            .onSuccess {
                if (dropped > 0) {
                    fallback.info("Usage logging recovered; $dropped row(s) were dropped while $target was unreachable")
                    dropped = 0
                }
                nextAttemptAt = 0
            }
            .onFailure {
                closeQuietly()
                // One warning per outage rather than one per request: the interval is what makes the
                // difference, since nothing is attempted again until it has passed.
                if (nextAttemptAt == 0L) {
                    fallback.warn("Could not write usage rows to $target; retrying in ${RETRY_INTERVAL_MILLIS / 1000}s", it)
                }
                nextAttemptAt = System.currentTimeMillis() + RETRY_INTERVAL_MILLIS
                dropped++
            }
    }

    private fun execute(target: String, sql: String, startedAt: Timestamp, bind: PreparedStatement.(Timestamp) -> Unit) {
        val handle = connect(target)
        handle.prepareStatement(sql).use {
            it.bind(startedAt)
            it.executeUpdate()
        }
    }

    private fun connect(target: String): Connection {
        val existing = connection
        // `isValid` rather than `isClosed`: a connection the server dropped is not closed on this
        // side, and every statement on it fails until something notices.
        if (existing != null && connectedTo == target && runCatching { existing.isValid(2) }.getOrDefault(false)) {
            return existing
        }
        closeQuietly()

        val opened = openWithSchema(target)
        connection = opened
        connectedTo = target
        return opened
    }

    private fun openWithSchema(target: String): Connection {
        val parsed = JdbcUrl.parse(target)

        if (parsed.database.isNotEmpty()) {
            open(parsed.serverUrl).use { server ->
                server.createStatement().use {
                    // Interpolated rather than bound: a database name cannot be a parameter in DDL.
                    // `JdbcUrl.parse` rejects anything that is not a plain identifier, which is what
                    // makes that safe -- a name is never allowed to carry a backtick out of the URL.
                    it.execute("CREATE DATABASE IF NOT EXISTS `${parsed.database}` " +
                        "DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci")
                }
            }
        }

        val opened = open(target)
        try {
            opened.createStatement().use { it.execute(SCHEMA) }
            // After the table it references: the foreign key is rejected outright if the parent is
            // not there yet, and on a fresh database the statement above is what puts it there.
            opened.createStatement().use { it.execute(TOOL_SCHEMA) }
            addMissingColumns(opened)
        } catch (e: Exception) {
            runCatching { opened.close() }
            throw e
        }
        return opened
    }

    private val ADDED_COLUMNS = listOf(
        "cost_usd" to "cost_usd DECIMAL(12,6) NULL AFTER cache_hit_rate",
    )

    private fun addMissingColumns(handle: Connection) {
        for ((name, definition) in ADDED_COLUMNS) {
            if (hasColumn(handle, name)) continue
            handle.createStatement().use { it.execute("ALTER TABLE $TABLE ADD COLUMN $definition") }
            fallback.info("Added the `$name` column to `$TABLE`")
        }
    }

    private fun hasColumn(handle: Connection, column: String): Boolean {
        val sql = "SELECT 1 FROM information_schema.COLUMNS " +
            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?"
        return handle.prepareStatement(sql).use { statement ->
            statement.setString(1, TABLE)
            statement.setString(2, column)
            statement.executeQuery().use { it.next() }
        }
    }

    private val driver by lazy { Driver() }

    private fun open(url: String): Connection =
        driver.connect(url, Properties())
            ?: throw IllegalStateException("the MySQL driver does not recognise this URL")

    private fun closeQuietly() {
        runCatching { connection?.close() }
        connection = null
        connectedTo = ""
    }

    private fun configuredUrl(): String = runCatching {
        val project = ProjectManager.getInstance().openProjects.firstOrNull() ?: return@runCatching ""
        val loaded = AgentConfigurations.getInstance(project).usageDatabase()
        loaded.error?.let { return@runCatching complain(it) }
        if (!loaded.database.isActive) return@runCatching ""
        try {
            JdbcUrl.prepare(loaded.database.url, TIMEOUTS)
        } catch (e: Exception) {
            // A typo in the URL is the one failure here that would otherwise leave no trace at all:
            // nothing is written, and nothing is attempted, so no connection ever fails to report it.
            complain("the usage database URL cannot be used: ${e.message}")
        }
    }.getOrDefault("")

    private fun complain(reason: String): String {
        if (badUrlWarned != reason) {
            badUrlWarned = reason
            fallback.warn("Nothing is being recorded to the usage database: $reason")
        }
        return ""
    }

    @Volatile
    private var badUrlWarned = ""

    private fun PreparedStatement.setDoubleOrNull(index: Int, value: Double?) {
        if (value == null) setNull(index, Types.DOUBLE) else setDouble(index, value)
    }

    private fun PreparedStatement.setDecimalOrNull(index: Int, value: BigDecimal?) {
        if (value == null) setNull(index, Types.DECIMAL) else setBigDecimal(index, value)
    }

    // --- schema -------------------------------------------------------------------------------------

    private val SCHEMA = """
        CREATE TABLE IF NOT EXISTS $TABLE (
            request_id         VARCHAR(64)  NOT NULL,
            conversation_id    VARCHAR(191) NOT NULL,
            response_id        VARCHAR(191) NULL,
            started_at         DATETIME(3)  NOT NULL,
            protocol           VARCHAR(64)  NULL,
            model              VARCHAR(191) NULL,
            url                TEXT         NULL,
            status_code        INT          NULL,
            duration_ms        BIGINT       NULL,
            error              TEXT         NULL,
            input_tokens       INT          NULL,
            cache_write_tokens INT          NULL,
            cache_read_tokens  INT          NULL,
            total_input_tokens INT          NULL,
            output_tokens      INT          NULL,
            cache_hit_rate     DOUBLE       NULL,
            cost_usd           DECIMAL(12,6) NULL,
            request_body       JSON         NULL,
            response_body      JSON         NULL,
            PRIMARY KEY (request_id),
            KEY ${TABLE}_conversation (conversation_id, started_at),
            KEY ${TABLE}_started_at (started_at)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    """.trimIndent()

    private val TOOL_SCHEMA = """
        CREATE TABLE IF NOT EXISTS $TOOL_TABLE (
            id              BIGINT       NOT NULL AUTO_INCREMENT,
            request_id      VARCHAR(64)  NOT NULL,
            conversation_id VARCHAR(191) NOT NULL,
            ordinal         INT          NOT NULL,
            tool_use_id     VARCHAR(191) NULL,
            tool_name       VARCHAR(191) NOT NULL,
            outcome         VARCHAR(32)  NOT NULL,
            finished_at     DATETIME(3)  NOT NULL,
            duration_ms     BIGINT       NULL,
            arguments       JSON         NULL,
            result          MEDIUMTEXT   NULL,
            argument_tokens INT          NULL,
            result_tokens   INT          NULL,
            PRIMARY KEY (id),
            UNIQUE KEY ${TOOL_TABLE}_call (request_id, ordinal),
            KEY ${TOOL_TABLE}_tool (tool_name, finished_at),
            KEY ${TOOL_TABLE}_conversation (conversation_id, finished_at),
            CONSTRAINT ${TOOL_TABLE}_request
                FOREIGN KEY (request_id) REFERENCES $TABLE (request_id) ON DELETE CASCADE
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    """.trimIndent()

    private fun asJson(body: String): String =
        runCatching { JsonParser.parseString(body) }.getOrNull()?.takeIf { !it.isJsonNull }?.toString()
            ?: JsonPrimitive(body).toString()

    // --- settings page ------------------------------------------------------------------------------

    fun test(url: String): String {
        val target = runCatching { JdbcUrl.prepare(url, TIMEOUTS) }.getOrElse { return "Bad URL: ${it.message}" }
        if (target.isEmpty()) return "No database URL is configured."

        return try {
            openWithSchema(target).use { handle ->
                val server = handle.metaData.databaseProductVersion
                val database = handle.catalog ?: JdbcUrl.parse(target).database
                // Both in one query rather than two, so the count and the total are read from the
                // same moment even if a turn is writing while this runs.
                val (rows, spent) = handle.createStatement().use { statement ->
                    statement.executeQuery("SELECT COUNT(*), COALESCE(SUM(cost_usd), 0) FROM $TABLE").use {
                        if (it.next()) it.getLong(1) to it.getBigDecimal(2) else 0L to BigDecimal.ZERO
                    }
                }
                val toolCalls = handle.createStatement().use { statement ->
                    statement.executeQuery("SELECT COUNT(*) FROM $TOOL_TABLE").use {
                        if (it.next()) it.getLong(1) else 0L
                    }
                }
                // Woken here as well as on a successful write, so a container that has just been
                // started begins recording without waiting out the retry interval.
                resetBackoff()
                "Connected to MySQL $server.\n" +
                    "Database `$database`, table `$TABLE` holds $rows row(s), " +
                    "${ModelPricing.format(spent)} of recorded cost.\n" +
                    "Table `$TOOL_TABLE` holds $toolCalls tool call(s)."
            }
        } catch (e: Exception) {
            "Could not connect: ${e.message}"
        }
    }

    @Synchronized
    private fun resetBackoff() {
        nextAttemptAt = 0
        dropped = 0
    }

    @Synchronized
    fun close() {
        closeQuietly()
    }
}
