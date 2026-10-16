package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID

internal class CommandOutputFile private constructor(
    val path: Path,
) : AutoCloseable {
    private val lock = Any()
    private val writer =
        Files.newBufferedWriter(
            path,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        )
    private var closed = false

    fun append(text: String) {
        synchronized(lock) {
            if (closed) return
            writer.append(text)
            writer.flush()
        }
    }

    fun digest(projectPath: String?): String =
        synchronized(lock) {
            if (!closed) writer.flush()
            val digest = ConsoleDigest(projectPath)
            Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
                val buffer = CharArray(BUFFER_SIZE)
                var count = reader.read(buffer)
                while (count >= 0) {
                    digest.append(String(buffer, 0, count))
                    count = reader.read(buffer)
                }
            }
            digest.snapshot()
        }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            writer.close()
        }
    }

    companion object {
        private const val BUFFER_SIZE = 8192

        fun create(project: Project): CommandOutputFile {
            val directory =
                Path.of(PathManager.getSystemPath(), "aiCodingAgentChat", "commands", project.locationHash)
            Files.createDirectories(directory)
            return CommandOutputFile(directory.resolve("${System.currentTimeMillis()}-${UUID.randomUUID()}.log"))
        }

        internal fun at(path: Path): CommandOutputFile = CommandOutputFile(path)
    }
}
