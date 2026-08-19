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

object ModelTrafficLog {

    private const val SIZE_LIMIT = 20 * 1024 * 1024
    private const val FILE_COUNT = 5

    private val pattern: Path =
        PathManager.getLogDir().resolve("independent-ai-plugin").resolve("model-traffic.%g.log")

    val currentFile: Path = pattern.resolveSibling("model-traffic.0.log")

    private val fallback = Logger.getInstance(ModelTrafficLog::class.java)

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
