package com.github.piotrszybicki.independentintelijaiplugin.mcp

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.SystemInfo
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class McpStdioTransport(config: McpServerConfig, workingDir: File?) : McpTransport {

    companion object {
        private val LOG = Logger.getInstance(McpStdioTransport::class.java)

        private const val STDERR_TAIL_LINES = 40

        fun resolveExecutable(command: String): String {
            if (!SystemInfo.isWindows) return command
            if (command.contains('/') || command.contains('\\')) return command

            val extensions = (System.getenv("PATHEXT") ?: ".COM;.EXE;.BAT;.CMD")
                .split(';')
                .filter { it.isNotBlank() }
            val directories = (System.getenv("PATH") ?: "").split(File.pathSeparatorChar)

            for (directory in directories) {
                if (directory.isBlank()) continue
                val candidates = listOf(File(directory, command)) +
                    extensions.map { File(directory, command + it) }
                candidates.firstOrNull { it.isFile }?.let { return it.absolutePath }
            }
            // Not on the PATH: hand it over unchanged so the failure comes from the spawn, whose
            // message names the command, rather than from a guess made here.
            return command
        }
    }

    private val gson = Gson()
    private val name = config.name
    private val process: Process
    private val writer: BufferedWriter
    private val pending = ConcurrentHashMap<Int, ArrayBlockingQueue<JsonObject>>()
    private val stderrTail = ArrayDeque<String>()

    @Volatile
    private var closed = false

    init {
        val executable = resolveExecutable(config.command.orEmpty())
        val builder = ProcessBuilder(listOf(executable) + config.args)
        workingDir?.takeIf { it.isDirectory }?.let { builder.directory(it) }
        builder.environment().putAll(config.env)

        process = try {
            builder.start()
        } catch (e: Exception) {
            throw McpException("could not start '${config.command} ${config.args.joinToString(" ")}': ${e.message}", e)
        }

        writer = BufferedWriter(OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8))
        thread("MCP stdout: $name") { readResponses() }
        thread("MCP stderr: $name") { readErrors() }
    }

    override fun request(message: JsonObject, timeoutMillis: Long): JsonObject {
        val id = message.get("id")?.asInt ?: throw McpException("internal: request without an id")
        val mailbox = ArrayBlockingQueue<JsonObject>(1)
        pending[id] = mailbox
        try {
            write(message)
            val response = mailbox.poll(timeoutMillis, TimeUnit.MILLISECONDS)
                ?: throw McpException(deathNote() ?: "no answer within ${timeoutMillis / 1000}s")
            return response
        } finally {
            pending.remove(id)
        }
    }

    override fun notify(message: JsonObject) = write(message)

    override fun close() {
        closed = true
        runCatching { writer.close() }
        // Closing stdin is how a well-behaved server is asked to stop; the destroy is for the ones
        // that do not take the hint, and the wait between them is what gives the first a chance.
        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroy()
        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        failPending("the server was shut down")
    }

    // --- plumbing -----------------------------------------------------------------------------

    private fun write(message: JsonObject) {
        if (closed || !process.isAlive) {
            throw McpException(deathNote() ?: "the server process is not running")
        }
        try {
            synchronized(writer) {
                // One message per line: the framing forbids a newline inside a message, and Gson
                // does not emit one, so nothing has to be escaped here.
                writer.write(gson.toJson(message))
                writer.write("\n")
                writer.flush()
            }
        } catch (e: Exception) {
            throw McpException(deathNote() ?: "could not write to the server: ${e.message}", e)
        }
    }

    private fun readResponses() {
        BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8)).useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val message = runCatching { JsonParser.parseString(line) }.getOrNull()?.takeIf { it.isJsonObject }
                if (message == null) {
                    // Servers that print banners to stdout are not conforming, but they are common
                    // enough that dropping the line is better than tearing the connection down.
                    LOG.info("MCP server '$name' wrote a non-JSON line to stdout: ${line.take(200)}")
                    continue
                }
                val obj = message.asJsonObject
                // runCatching because the id is the server's to echo: a string one would otherwise
                // end the reader thread rather than simply not matching.
                val id = runCatching { obj.get("id")?.asInt }.getOrNull()
                if (id == null) {
                    // A notification -- progress, a tools/list_changed. Nothing consumes these yet;
                    // the tool list is re-read every turn, which covers the case they announce.
                    LOG.debug("MCP server '$name' notification: ${obj.get("method")}")
                    continue
                }
                // offer, not put: a caller that gave up has already removed its mailbox, and the
                // reader must not block on a response nobody is waiting for any more.
                pending[id]?.offer(obj)
            }
        }
        // stdout reached EOF, so the process is gone or going. Waiters would otherwise sit until
        // their timeout for an answer that can no longer come.
        if (!closed) failPending(deathNote() ?: "the server closed its output")
    }

    private fun readErrors() {
        BufferedReader(InputStreamReader(process.errorStream, StandardCharsets.UTF_8)).useLines { lines ->
            for (line in lines) {
                synchronized(stderrTail) {
                    stderrTail.addLast(line)
                    if (stderrTail.size > STDERR_TAIL_LINES) stderrTail.removeFirst()
                }
                LOG.info("MCP server '$name' stderr: $line")
            }
        }
    }

    private fun failPending(reason: String) {
        val failure = JsonObject().apply {
            add("error", JsonObject().apply {
                addProperty("code", -32000)
                addProperty("message", reason)
            })
        }
        pending.values.forEach { it.offer(failure) }
    }

    private fun deathNote(): String? {
        if (process.isAlive) return null
        val tail = synchronized(stderrTail) { stderrTail.joinToString("\n") }.trim()
        val exit = runCatching { process.exitValue().toString() }.getOrDefault("?")
        return buildString {
            append("the server process exited with code $exit")
            if (tail.isNotEmpty()) append(". Its last output was:\n$tail")
        }
    }

    private fun thread(name: String, body: () -> Unit) {
        Thread({ runCatching(body).onFailure { LOG.info("$name ended: ${it.message}") } }, name)
            .apply { isDaemon = true }
            .start()
    }
}
