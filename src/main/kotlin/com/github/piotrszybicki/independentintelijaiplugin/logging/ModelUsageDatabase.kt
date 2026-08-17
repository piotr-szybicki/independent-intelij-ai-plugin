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

/**
 * One row per request in a MySQL table -- what a conversation cost, and which request was the
 * expensive one.
 *
 * A client and nothing more: the server is whatever the `usage-database` section of
 * [AgentConfiguration.FILE_NAME] points at -- see
 * [com.github.piotrszybicki.independentintelijaiplugin.settings.UsageDatabaseConfig] -- and starting
 * it, backing it up and keeping it running are outside this plugin. What is here is the schema it
 * needs, created on the way in if it is not there, and the four writes that fill a row.
 *
 * ### One row, four writes
 *
 * A request is written before it goes out and updated as it comes back, so a request that never
 * returns still has a row saying so -- which is exactly the case worth looking into. Every write is
 * an upsert keyed on `request_id`, so the order they arrive in does not matter and switching the
 * setting on mid-session does not produce half-rows that reference nothing.
 *
 * ### Off the request thread
 *
 * Unlike a file, this write travels: the server may be a container that is not running, a host that
 * is not routable, or one that accepts the connection and then stops answering. Doing that inline
 * would put the agent's turn behind a network timeout, so every write is handed to a background
 * executor and the caller returns immediately. The timestamp is read on the calling thread and
 * carried along, so a row says when the request happened rather than when the queue got to it.
 *
 * A server that is not answering is dropped rather than queued: after a failure nothing is attempted
 * for [RETRY_INTERVAL_MILLIS], and rows minted in that window are counted and discarded. A log is
 * not worth an unbounded queue of retries, and a container that comes back up starts recording again
 * on its own.
 *
 * ### What the columns mean
 *
 * The three input columns are disjoint and sum to `total_input_tokens`: `input_tokens` is what was
 * billed at full price, and what came from the cache is counted in its own column rather than in
 * that one. Both wire protocols are normalised to this shape before they get here -- the OpenAI ones
 * report the cached count *inside* their input total, and `OpenAiProtocol` subtracts it back out.
 * Without that, adding the columns would count every cached token twice.
 *
 * `cost_usd` is those counts priced at [ModelPricing]'s rates -- an estimate at the rates written
 * into that file rather than a bill, and NULL for a model it has no price for. It is the same number
 * the chat window draws under the reply, from the same function, which is why it is written here
 * rather than worked out on the server: the figure the user was shown is the figure that is kept.
 * It is also what makes rows from different models comparable at all, since tokens on a cheap model
 * and tokens on an expensive one are the same number and nothing like the same money.
 *
 * `protocol`, `url`, `status_code`, `duration_ms` and `error` are here because [ModelExchangeLog] no
 * longer carries them: its files hold the bodies and nothing else. `response_id` is the provider's
 * own id for the message -- the only identifier that means anything on the provider's side, and what
 * a support ticket has to quote.
 *
 * ### The second table
 *
 * [TOOL_TABLE] holds the tool calls, one row each, keyed back to the request whose response asked
 * for them -- see [recordToolCall]. The bodies in [TABLE] already contain the same calls, but only
 * as JSON inside two columns: what the join buys is that a tool call becomes a row, so how long a
 * tool takes, how often it fails and how much of the context window its output eats are questions
 * `GROUP BY tool_name` answers.
 *
 * `request_body` and `response_body` hold the same two bodies as those files, so a row is the whole
 * exchange without leaving SQL. The files stay: they are what an editor folds and a diff compares,
 * and `conversation_id` with `request_id` names the directory holding them. The cost of the columns
 * is that the table now grows by roughly the size of the conversation per request rather than by a
 * few hundred bytes -- the same bargain the files make, made twice.
 */
object ModelUsageDatabase {

    private val fallback = Logger.getInstance(ModelUsageDatabase::class.java)

    const val TABLE = "model_requests"

    /**
     * One row per tool call, hanging off the request that asked for it.
     *
     * A table of its own rather than more columns on [TABLE], because the relationship is one to
     * many: a single response can ask for several tools at once (see `toolUseBlocks` in
     * `AICodingAgent.run`), and the alternative -- a JSON array in a column -- is the shape that
     * makes "which tool is slowest", "which one fails most" and "what does read_project_file
     * actually cost us" queries that cannot be written.
     */
    const val TOOL_TABLE = "model_tool_calls"

    /**
     * How long to leave a server alone after a failed write.
     *
     * Long enough that a container which is down does not mean a connection attempt per request, and
     * short enough that one which has just come up starts being written to without restarting the
     * IDE.
     */
    private const val RETRY_INTERVAL_MILLIS = 30_000L

    /**
     * The connection settings appended to the URL when it does not set them itself.
     *
     * Both matter more here than the driver's defaults do. Without them a host that is routable but
     * not answering holds the writer thread for the OS-level TCP timeout, which is minutes: this is
     * a log, and it should give up long before that.
     */
    private val TIMEOUTS = mapOf("connectTimeout" to "5000", "socketTimeout" to "10000")

    /**
     * Single-threaded so writes to one row stay in the order they were made, and the platform's pool
     * rather than a thread of its own so it winds down with the IDE instead of having to be closed.
     */
    private val executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("AI plugin usage log", 1)

    /** Stands in for a request made outside any chat, matching [ModelExchangeLog]'s directory name. */
    private const val UNASSIGNED = "unassigned"

    private var connection: Connection? = null

    /** The URL [connection] was opened against, so a URL edited in the settings reconnects. */
    private var connectedTo: String = ""

    /** Nothing is attempted before this, so a server that is down costs one attempt per interval. */
    private var nextAttemptAt = 0L

    /** Rows thrown away since the last successful write, reported once the server comes back. */
    private var dropped = 0

    // --- writes -------------------------------------------------------------------------------------

    /**
     * Opens the row, before the request goes out. [ModelExchangeLog.newRequestId] minted the id, so
     * this and the two bodies on disk carry the same one.
     */
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

    /**
     * Closes the row with what came back. Written before the status code is checked, so a rejected
     * request is a row saying what it was rejected with rather than a row that stops at the request.
     */
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

    /**
     * Closes the row for a request that never got an answer -- a timeout, a refused connection, a
     * TLS failure.
     *
     * Worth counting rather than only reporting to the user: a provider that is intermittently
     * unreachable shows up here as rows with an `error` and a `duration_ms` at the client's timeout,
     * which is a pattern no single failed turn looks like.
     */
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

    /**
     * Fills in the token columns and what they cost, once the response has been parsed far enough to
     * have them.
     *
     * Separate from [recordResponse] because a response can arrive without a usage block -- an error
     * body has none -- and the row is worth having either way.
     *
     * [model] is here only to price the counts: it is written by [recordRequest] already, and a
     * second copy of it in this statement would be a chance for the two to disagree.
     */
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

    /** The models already complained about, so an unpriced one is a line per session and not per request. */
    private val unpricedWarned = ConcurrentHashMap.newKeySet<String>()

    /**
     * Says once that a model has no price, so a column of NULLs has an explanation somewhere.
     *
     * Worth a line rather than silence: the case this catches is a model released since
     * [ModelPricing]'s table was last edited, where the rows keep being written and only the cost is
     * missing -- which looks like the feature working until someone sums the column.
     */
    private fun reportUnpriced(model: String) {
        if (!unpricedWarned.add(model)) return
        fallback.info(
            "No price is configured for '$model', so its rows record no cost and no figure is shown " +
                "in the chat. Priced models: ${ModelPricing.pricedModels().joinToString(", ")}",
        )
    }

    // --- tool calls ---------------------------------------------------------------------------------

    /**
     * Records one tool call against the request whose response asked for it.
     *
     * [ordinal] is the call's place in that response, counted from zero, and it is what makes the
     * row identifiable: several tools can be asked for at once, and `tool_use_id` -- the provider's
     * own id for the block -- is the natural key only as long as there is one, which a compatible
     * server that omits it does not guarantee. `(request_id, ordinal)` is unique either way, so a
     * write that is somehow made twice updates its row instead of adding a second.
     *
     * Written after the tool has returned rather than before it starts, unlike [recordRequest]. A
     * tool the loop never finished leaves no row at all here, which is the one thing this shape
     * cannot record -- but the agent answers every `tool_use` block it opens, cancels included, so
     * the case is a turn that died outright, and that is what the request row's `error` is for.
     *
     * [argumentTokens] and [resultTokens] are `TokenCounter`'s estimate, not the provider's count:
     * the arguments and the result are both part of the *next* request's input, so this is what
     * this call will cost to carry from here on, and summing the column per tool is the closest
     * thing to an answer for which tool is eating the context window. Nothing bills on it -- the
     * billed figures are [TABLE]'s token columns, which the provider reports.
     */
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

    /**
     * How much of a tool result is kept.
     *
     * A cap rather than the whole thing because this column is the one place the plugin writes
     * unbounded output to a server: a `read_project_file` on a generated source file is megabytes,
     * and a single statement over MySQL's `max_allowed_packet` -- 4MB on a 5.7 server that has not
     * been tuned -- fails the write rather than truncating it, taking the row with it. A million
     * characters of the ASCII that tool output almost always is comes to about a megabyte, which
     * leaves room for the rest of the statement under even that limit, and it is already far past
     * anything anyone reads back.
     *
     * `result_tokens` is counted on the untruncated output, so the figure a row reports is what the
     * call actually cost even when what it kept is shorter.
     */
    private const val MAX_RESULT_CHARS = 1_000_000

    private fun truncated(result: String): String =
        if (result.length <= MAX_RESULT_CHARS) {
            result
        } else {
            result.take(MAX_RESULT_CHARS) +
                "\n\n[... truncated: ${result.length - MAX_RESULT_CHARS} more characters were not recorded]"
        }

    // --- plumbing -----------------------------------------------------------------------------------

    /**
     * Hands one statement to the executor, or drops it.
     *
     * The timestamp is taken here rather than in the task, so a row records when the request was
     * made even if the queue is behind or the server was briefly away.
     */
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

    /**
     * The open connection, or a new one with the schema in place.
     *
     * Reconnects when the URL has changed under it, so editing the field in the settings takes
     * effect on the next request rather than on the next IDE start.
     */
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

    /**
     * Connects, creating the database and the table if they are not there.
     *
     * Two connections on the way in, because the first cannot be made to a database that does not
     * exist yet: the server is reached without one, `CREATE DATABASE IF NOT EXISTS` runs, and only
     * then is the real connection opened. Both statements are `IF NOT EXISTS`, so the usual case is
     * two cheap round trips once per session and nothing else.
     */
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

    /**
     * The columns added after the table was first shipped, `name to definition`.
     *
     * Kept as a list rather than folded into [SCHEMA] alone because `CREATE TABLE IF NOT EXISTS`
     * does nothing at all to a table that is already there: without this, a user who has been
     * recording for weeks gets a table missing `cost_usd`, every usage insert fails on the unknown
     * column, and the only fix is dropping the history the table exists to keep.
     */
    private val ADDED_COLUMNS = listOf(
        "cost_usd" to "cost_usd DECIMAL(12,6) NULL AFTER cache_hit_rate",
    )

    /**
     * Brings an existing table up to the current shape.
     *
     * Checked before altering rather than altering and ignoring the error, because MySQL has no
     * `ADD COLUMN IF NOT EXISTS` and swallowing whatever an `ALTER` throws would also swallow the
     * failures worth seeing -- no privilege to alter, a table locked by something else. The check is
     * one row from `information_schema` against the session's own database, which is a few
     * microseconds once per connection.
     */
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

    /**
     * The driver called directly rather than through `DriverManager`.
     *
     * `DriverManager` searches the calling class's own classloader, which in an IDE is this plugin's
     * -- and the registration it relies on is global, shared with every other plugin that ships a
     * JDBC driver. Holding the driver here skips both problems. Credentials and every other option
     * ride in the URL, which is what the driver parses out of it anyway.
     */
    private val driver by lazy { Driver() }

    private fun open(url: String): Connection =
        driver.connect(url, Properties())
            ?: throw IllegalStateException("the MySQL driver does not recognise this URL")

    private fun closeQuietly() {
        runCatching { connection?.close() }
        connection = null
        connectedTo = ""
    }

    /**
     * The URL to write to, expanded and with timeouts applied, or empty when nothing should be
     * written.
     *
     * Read from the project's [AgentConfiguration.FILE_NAME] per call rather than cached, so both
     * the switch and the URL take effect on the next request -- the same as every other thing that
     * file says. Behind a `runCatching` because this is reached from a pooled thread and a service
     * lookup that fails during shutdown is not a reason to lose the request.
     *
     * The first open project, because there is no project here to ask: this and the client above it
     * are application-level objects, and threading a project through four record calls to settle a
     * question that only arises with two projects open at once would cost more than it answers. With
     * one project open -- which is the case this is used in -- it is exactly the file the settings
     * page shows.
     */
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

    /**
     * Says once that nothing is being recorded and why, and reports back the empty URL that means
     * it. Once per distinct complaint, so a typo left in the file is a line in `idea.log` rather
     * than a stream of them.
     */
    private fun complain(reason: String): String {
        if (badUrlWarned != reason) {
            badUrlWarned = reason
            fallback.warn("Nothing is being recorded to the usage database: $reason")
        }
        return ""
    }

    /** The last complaint already made, so a typo is one line in idea.log and not a stream. */
    @Volatile
    private var badUrlWarned = ""

    private fun PreparedStatement.setDoubleOrNull(index: Int, value: Double?) {
        if (value == null) setNull(index, Types.DOUBLE) else setDouble(index, value)
    }

    private fun PreparedStatement.setDecimalOrNull(index: Int, value: BigDecimal?) {
        if (value == null) setNull(index, Types.DECIMAL) else setBigDecimal(index, value)
    }

    // --- schema -------------------------------------------------------------------------------------

    /**
     * `VARCHAR(191)` on the indexed text columns rather than `TEXT`: an index needs a bounded key,
     * and 191 is the longest utf8mb4 column that fits the 767-byte prefix limit an older server or a
     * non-DYNAMIC row format still enforces. Both hold ids that are nowhere near it.
     *
     * The bodies are MySQL's own `JSON` type rather than `TEXT`, so they can be queried instead of
     * only read -- `request_body->>'$.model'`, or the length of the messages array, is then a column
     * expression and can be indexed as a generated one. What that costs is that MySQL parses and
     * normalises a JSON value on the way in, so what comes back has its object keys reordered, its
     * whitespace gone and any duplicate key dropped. It is the same document, not the same bytes;
     * the files under `exchanges/` are still the copy to diff against.
     *
     * This creates the table; [ADDED_COLUMNS] is what brings an older one up to date. A column added
     * here has to be added there as well, or the table an existing user already has keeps the shape
     * it was created with and every insert naming the new column fails.
     */
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

    /**
     * The tool calls one response asked for, one row each -- see [recordToolCall].
     *
     * The foreign key is what makes the table worth having in this shape: a tool call without the
     * request that asked for it says nothing about why it happened, and `ON DELETE CASCADE` means
     * clearing out old requests takes their calls with them rather than leaving rows pointing at
     * nothing. It also means a row whose parent was never written is refused -- which is possible,
     * since a request minted while the server was unreachable is dropped rather than queued -- and
     * that is the right answer: recording the call would only invent a request that no row
     * describes.
     *
     * `arguments` is MySQL's `JSON`, the same bargain [TABLE]'s bodies make: queryable
     * (`arguments->>'$.path'`), and reformatted on the way in rather than kept byte for byte.
     * `result` is `MEDIUMTEXT` instead, because a tool result is text -- a file, a diff, a stack
     * trace -- and storing it as a JSON string would double every quote and newline in it for
     * nothing.
     *
     * `tool_name` is indexed on its own: the queries this table exists for group by it.
     *
     * Nothing here needs [ADDED_COLUMNS] yet, because the table is new: everyone gets it created in
     * this shape. A column added to it later does -- and [addMissingColumns] only walks [TABLE], so
     * it would need the table naming as well as the column.
     */
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

    /**
     * [body] as something a `JSON` column will accept.
     *
     * A body is normally JSON already, but not always: an error from a gateway in front of the
     * provider is often an HTML page, and MySQL rejects a `JSON` column value it cannot parse
     * outright. Anything unparseable is stored as a JSON string instead, so the row is still written
     * and the body is still there to read -- which is exactly the case it is most wanted in.
     */
    private fun asJson(body: String): String =
        runCatching { JsonParser.parseString(body) }.getOrNull()?.takeIf { !it.isJsonNull }?.toString()
            ?: JsonPrimitive(body).toString()

    // --- settings page ------------------------------------------------------------------------------

    /**
     * Connects once and reports what is there, for the button on the settings page.
     *
     * Runs against the URL the file holds as it stands, re-read when the button is pressed rather
     * than taken from what the page is displaying: the file is edited in the editor behind the
     * dialog, and the point is to find out whether what is in it now works. It creates the database
     * and table if they are not there, which is the same thing the first request would have done --
     * so a green answer here means the next request has nothing left to set up.
     */
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

    /**
     * Drops the connection. Called when the setting is switched off or the URL is changed, so a
     * server the plugin has been told to stop writing to is not left with an open session.
     */
    @Synchronized
    fun close() {
        closeQuietly()
    }
}
