package com.github.piotrszybicki.independentintelijaiplugin.logging

import com.github.piotrszybicki.independentintelijaiplugin.settings.AgentConfiguration
import com.github.piotrszybicki.independentintelijaiplugin.settings.AgentConfigurations
import com.intellij.openapi.project.Project
import com.mysql.cj.jdbc.Driver
import java.sql.Connection
import java.sql.Statement
import java.util.Properties

/**
 * Where documentation comments live once they are out of the code.
 *
 * One row per comment, `id` and the text, in the same MySQL database as [ModelUsageDatabase] --
 * whatever the `usage-database` section of [AgentConfiguration.FILE_NAME] points at. The code keeps
 * only `// comment_id: <id>` where the comment was, so this table is the documentation now: dropping
 * it loses it, and nothing in the code says what any of these rows belonged to.
 *
 * ### Why not [ModelUsageDatabase]'s connection
 *
 * That one is owned by a single-threaded executor doing fire-and-forget writes, and a JDBC
 * connection is not safe to use from two threads at once. Everything here is synchronous -- an
 * insert has to come back with the id before the code can be edited to point at it -- so it takes a
 * connection of its own and serialises on this object instead.
 *
 * The connection is opened on first use and kept, reopening when the configured URL changes so an
 * edit to the settings file takes effect on the next call rather than on the next IDE start.
 */
object CommentDatabase {

    const val TABLE = "code_comments"

    /**
     * Anything that stopped the call from happening: no URL configured, a server that will not
     * answer, a row that is not there. Carries a message meant to be shown to the user or handed
     * back to the model as a tool result, so it says what to do rather than what threw.
     */
    class Unavailable(message: String) : Exception(message)

    /**
     * `BLOB` as specified, so the text is stored as bytes and nothing normalises it on the way in
     * or out -- what comes back is what went in, byte for byte, which is what putting a comment back
     * into a file needs. It caps a single comment at 64KB; a doc comment that long is not one this
     * was built for, and the server rejects the insert rather than truncating it.
     */
    private val SCHEMA = """
        CREATE TABLE IF NOT EXISTS $TABLE (
            id      BIGINT NOT NULL AUTO_INCREMENT,
            comment BLOB   NULL,
            PRIMARY KEY (id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    """.trimIndent()

    /** Same values and the same reasoning as [ModelUsageDatabase]: this must fail fast, not hang. */
    private val TIMEOUTS = mapOf("connectTimeout" to "5000", "socketTimeout" to "10000")

    /** Held directly rather than through `DriverManager` -- see [ModelUsageDatabase] for why. */
    private val driver by lazy { Driver() }

    private var connection: Connection? = null
    private var connectedTo: String = ""

    /** Stores [comment] and returns the id the server gave it. */
    @Synchronized
    fun insert(project: Project, comment: String): Long {
        val handle = connect(project)
        handle.prepareStatement("INSERT INTO $TABLE (comment) VALUES (?)", Statement.RETURN_GENERATED_KEYS)
            .use { statement ->
                statement.setBytes(1, comment.toByteArray(Charsets.UTF_8))
                statement.executeUpdate()
                statement.generatedKeys.use { keys ->
                    if (keys.next()) return keys.getLong(1)
                }
            }
        // AUTO_INCREMENT always reports a key, so this is a server that is not the one we think it
        // is -- worth saying rather than returning an id nothing can be looked up by.
        throw Unavailable("the insert into `$TABLE` returned no id")
    }

    /** The comment stored under [id], or null when there is no such row. */
    @Synchronized
    fun read(project: Project, id: Long): String? {
        val handle = connect(project)
        handle.prepareStatement("SELECT comment FROM $TABLE WHERE id = ?").use { statement ->
            statement.setLong(1, id)
            statement.executeQuery().use { rows ->
                if (!rows.next()) return null
                return rows.getBytes(1)?.toString(Charsets.UTF_8).orEmpty()
            }
        }
    }

    /** How many comments are stored. For the settings page's connection test. */
    @Synchronized
    fun count(project: Project): Long {
        val handle = connect(project)
        handle.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM $TABLE").use { rows ->
                return if (rows.next()) rows.getLong(1) else 0L
            }
        }
    }

    /** Drops the connection, so a URL changed in the settings is not left with an open session. */
    @Synchronized
    fun close() {
        runCatching { connection?.close() }
        connection = null
        connectedTo = ""
    }

    /**
     * The open connection, reconnecting when the configured URL has changed under it.
     *
     * `isValid` rather than `isClosed`: a connection the server dropped is not closed on this side,
     * and every statement on it fails until something notices.
     */
    private fun connect(project: Project): Connection {
        val target = configuredUrl(project)

        val existing = connection
        if (existing != null && connectedTo == target && runCatching { existing.isValid(2) }.getOrDefault(false)) {
            return existing
        }
        close()

        val opened = openWithSchema(target)
        connection = opened
        connectedTo = target
        return opened
    }

    /**
     * Connects, creating the database and the table if they are not there.
     *
     * Two connections on the way in, because the first cannot be made to a database that does not
     * exist yet -- the same dance [ModelUsageDatabase] does, and for the same reason: the comment
     * tools may well be the first thing this project ever points at that server.
     */
    private fun openWithSchema(target: String): Connection {
        val parsed = JdbcUrl.parse(target)

        if (parsed.database.isNotEmpty()) {
            open(parsed.serverUrl).use { server ->
                server.createStatement().use {
                    // Interpolated because a database name cannot be bound in DDL; JdbcUrl.parse
                    // rejects anything that is not a plain identifier, which is what makes it safe.
                    it.execute(
                        "CREATE DATABASE IF NOT EXISTS `${parsed.database}` " +
                            "DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci",
                    )
                }
            }
        }

        val opened = open(target)
        try {
            opened.createStatement().use { it.execute(SCHEMA) }
        } catch (e: Exception) {
            runCatching { opened.close() }
            throw e
        }
        return opened
    }

    private fun open(url: String): Connection =
        try {
            driver.connect(url, Properties())
                ?: throw Unavailable("the MySQL driver does not recognise the configured URL")
        } catch (e: Unavailable) {
            throw e
        } catch (e: Exception) {
            throw Unavailable("could not reach the comment database: ${e.message}")
        }

    /**
     * The URL to use, expanded and with timeouts applied.
     *
     * Read per call from the project's [AgentConfiguration.FILE_NAME], like everything else that
     * file says. Unlike the usage log, a missing or switched-off database is an error rather than
     * silence: the caller is about to take a comment out of a file, or to look one up on the model's
     * behalf, and neither can be done quietly with nowhere to put it.
     */
    private fun configuredUrl(project: Project): String {
        val loaded = AgentConfigurations.getInstance(project).usageDatabase()
        loaded.error?.let { throw Unavailable(it) }
        if (!loaded.database.isActive) {
            throw Unavailable(
                "no database is configured: add a `usage-database` section with a URL to " +
                    "${AgentConfiguration.FILE_NAME} and switch it on",
            )
        }
        return try {
            JdbcUrl.prepare(loaded.database.url, TIMEOUTS)
        } catch (e: Exception) {
            throw Unavailable("the database URL cannot be used: ${e.message}")
        }
    }
}
