package com.github.piotrszybicki.independentintelijaiplugin.logging

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.logging.FileHandler
import java.util.logging.Formatter
import java.util.logging.Level
import java.util.logging.LogRecord

/**
 * The log the model request/response bodies go to.
 *
 * `Logger.getInstance` writes to idea.log, where a single turn's request body buries whatever else
 * the IDE was saying at the time -- and the traffic is the one thing worth reading on its own. This
 * keeps a `java.util.logging` logger of its own, detached from the root logger so nothing travels
 * back up into idea.log, writing to a rotating file under the IDE's log directory.
 *
 * The platform has its own rolling handler, but it and `JulLogger` are both `@ApiStatus.Internal`;
 * the JDK's [FileHandler] rotates just as well and cannot be taken away by a platform update.
 */
object ModelTrafficLog {

    private const val SIZE_LIMIT = 20 * 1024 * 1024
    private const val FILE_COUNT = 5

    /**
     * Under the IDE's own log directory, so "Collect Logs and Diagnostic Data" picks the file up
     * along with idea.log.
     *
     * `%g` is [FileHandler]'s generation number and it is not decoration: generation 0 is always the
     * file being written now, and the older ones age upwards to `model-traffic.4.log`.
     */
    private val pattern: Path =
        PathManager.getLogDir().resolve("independent-ai-plugin").resolve("model-traffic.%g.log")

    /** Where to tell the user to look -- the generation that is being written right now. */
    val currentFile: Path = pattern.resolveSibling("model-traffic.0.log")

    private val fallback = Logger.getInstance(ModelTrafficLog::class.java)

    /**
     * Null when the file could not be opened, which is a reason to log somewhere else rather than to
     * take the plugin down on its first request -- [info] falls back to idea.log.
     *
     * Held in a field on purpose: `java.util.logging` keeps only a weak reference to a logger it
     * hands out, and a collected one loses the handler configured below.
     */
    private val fileLogger: java.util.logging.Logger? = runCatching { openFileLogger() }
        .onFailure { fallback.warn("Could not open $currentFile; model traffic stays in idea.log", it) }
        .getOrNull()

    fun info(message: String) {
        val logger = fileLogger
        if (logger == null) fallback.info(message) else logger.info(message)
    }

    private fun openFileLogger(): java.util.logging.Logger {
        Files.createDirectories(pattern.parent)

        val handler = FileHandler(pattern.toString(), SIZE_LIMIT, FILE_COUNT, true).apply {
            formatter = TrafficFormatter()
            level = Level.INFO
        }

        return java.util.logging.Logger.getLogger("independent-ai-plugin.traffic").apply {
            // Without this the records also travel up to the root logger, which is where the IDE's
            // own handler sits -- the traffic would land in both files instead of only in this one.
            useParentHandlers = false
            level = Level.INFO
            handlers.forEach { removeHandler(it) }
            addHandler(handler)
            // Left in idea.log on purpose: the breadcrumb that says where the traffic went.
            fallback.info("Model request/response log: $currentFile")
        }
    }

    /** idea.log's shape, near enough that the two files read the same way side by side. */
    private class TrafficFormatter : Formatter() {

        private val timestamp = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss,SSS")
            .withZone(ZoneId.systemDefault())

        override fun format(record: LogRecord): String {
            val thrown = record.thrown?.stackTraceToString()?.let { "\n$it" } ?: ""
            // `record.message`, not `formatMessage(record)`: the latter runs MessageFormat, and a
            // JSON body is nothing but the braces MessageFormat treats as placeholders.
            return "${timestamp.format(Instant.ofEpochMilli(record.millis))} " +
                "[${record.level.name}] ${record.message.orEmpty()}$thrown\n"
        }
    }
}
