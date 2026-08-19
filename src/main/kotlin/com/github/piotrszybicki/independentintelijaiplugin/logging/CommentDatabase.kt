package com.github.piotrszybicki.independentintelijaiplugin.logging

import com.github.piotrszybicki.independentintelijaiplugin.settings.AgentConfiguration
import com.github.piotrszybicki.independentintelijaiplugin.settings.AgentConfigurations
import com.intellij.openapi.project.Project
import com.mysql.cj.jdbc.Driver
import java.sql.Connection
import java.sql.Statement
import java.util.Properties

object CommentDatabase {

    const val TABLE = "code_comments"

    class Unavailable(message: String) : Exception(message)

    private val SCHEMA = """
        CREATE TABLE IF NOT EXISTS $TABLE (
            id      BIGINT NOT NULL AUTO_INCREMENT,
            comment BLOB   NULL,
            PRIMARY KEY (id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    """.trimIndent()

    private val TIMEOUTS = mapOf("connectTimeout" to "5000", "socketTimeout" to "10000")

    private val driver by lazy { Driver() }

    private var connection: Connection? = null
    private var connectedTo: String = ""

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

    @Synchronized
    fun count(project: Project): Long {
        val handle = connect(project)
        handle.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM $TABLE").use { rows ->
                return if (rows.next()) rows.getLong(1) else 0L
            }
        }
    }

    @Synchronized
    fun close() {
        runCatching { connection?.close() }
        connection = null
        connectedTo = ""
    }

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
