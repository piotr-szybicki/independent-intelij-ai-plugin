package com.github.piotrszybicki.independentintelijaiplugin.mcp

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** One tool as the server describes it, before it is adapted to the Messages API's tool shape. */
data class McpToolDescriptor(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

/**
 * A connection to one MCP server: the handshake, the tool list, and tool calls.
 *
 * Deliberately blocking. Every caller is either the agent's pooled thread, which has nothing else
 * to do until the tool answers, or a background task in settings -- so the asynchrony the protocol
 * allows for would buy nothing here and cost a threading model to reason about.
 */
class McpClient(private val config: McpServerConfig, private val workingDir: File?) : AutoCloseable {

    companion object {
        private val LOG = Logger.getInstance(McpClient::class.java)
        private val GSON = Gson()

        /**
         * The revision this client implements. A server that speaks an older one answers with its
         * own version and the exchange continues -- the parts used here have not changed across
         * revisions -- so the value is a preference rather than a requirement.
         */
        private const val PROTOCOL_VERSION = "2025-06-18"

        /** Tool results go straight into the conversation, where an unbounded one is expensive. */
        private const val MAX_RESULT_CHARS = 30_000

        /**
         * Flattens a `tools/call` result into the text the Messages API wants back.
         *
         * Only text survives as text; the other content types are named and measured instead. That
         * is a real limitation -- an MCP server returning an image has it described rather than
         * seen -- but the alternative is threading image blocks through a tool_result, which the
         * transcript and the history format are not built for.
         */
        fun renderResult(result: JsonObject): String {
            val rendered = result.getAsJsonArray("content").orEmpty()
                .mapNotNull { block -> (block as? JsonObject)?.let(::renderBlock) }
            val structured = result.getAsJsonObject("structuredContent")

            var text = rendered.joinToString("\n\n").trim()
            // Servers that return structured output repeat it as text for older clients; only fall
            // back to the raw JSON when they have not.
            if (text.isEmpty() && structured != null) text = GSON.toJson(structured)
            if (text.isEmpty()) text = "(the tool returned no content)"

            if (text.length > MAX_RESULT_CHARS) {
                text = text.take(MAX_RESULT_CHARS) +
                    "\n\n[TRUNCATED: ${text.length - MAX_RESULT_CHARS} more characters omitted]"
            }

            // `isError` means the tool ran and failed, which the model should see and react to --
            // not a protocol failure, which never gets this far.
            return if (result.get("isError")?.asBoolean == true) "Error: $text" else text
        }

        private fun renderBlock(block: JsonObject): String? = when (block.get("type")?.asString) {
            "text" -> block.get("text")?.asString
            "image", "audio" -> {
                val mime = block.get("mimeType")?.asString ?: "unknown type"
                val bytes = block.get("data")?.asString?.length?.times(3)?.div(4) ?: 0
                "[${block.get("type")?.asString}: $mime, ~$bytes bytes -- this client cannot show it]"
            }
            "resource" -> block.getAsJsonObject("resource")?.let { resource ->
                val uri = resource.get("uri")?.asString.orEmpty()
                resource.get("text")?.asString?.let { "$uri:\n$it" } ?: "[binary resource: $uri]"
            }
            "resource_link" -> "[resource: ${block.get("uri")?.asString}]"
            else -> block.get("text")?.asString
        }

        private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray()
    }

    private val nextId = AtomicInteger(1)
    private var transport: McpTransport? = null

    /** What the server called itself at the handshake; used in status messages, not for routing. */
    var serverInfo: String = config.name
        private set

    /**
     * Runs the handshake and returns everything the server offers.
     *
     * Every failure from here is an [McpException] naming the server, because the caller's job is
     * to carry on with the servers that did work rather than to distinguish the causes.
     */
    fun connect(): List<McpToolDescriptor> {
        val resolved = config.resolved()
        val timeout = resolved.timeoutSeconds * 1000L

        val opened = if (resolved.isStdio) {
            McpStdioTransport(resolved, workingDir)
        } else {
            McpHttpTransport(resolved)
        }
        transport = opened

        val initialize = McpRpc.request(nextId.getAndIncrement(), "initialize", JsonObject().apply {
            addProperty("protocolVersion", PROTOCOL_VERSION)
            // No client capabilities are advertised: this client offers the server no sampling, no
            // roots and no elicitation, and claiming otherwise would have servers call back into
            // nothing.
            add("capabilities", JsonObject())
            add("clientInfo", JsonObject().apply {
                addProperty("name", "aicodingagent-intellij")
                addProperty("version", "1.0")
            })
        })

        val result = McpRpc.resultOf(opened.request(initialize, timeout), "initialize")
        result.getAsJsonObject("serverInfo")?.let { info ->
            val name = info.get("name")?.asString ?: config.name
            val version = info.get("version")?.asString
            serverInfo = if (version == null) name else "$name $version"
        }
        (opened as? McpHttpTransport)?.protocolVersion = result.get("protocolVersion")?.asString ?: PROTOCOL_VERSION

        opened.notify(McpRpc.notification("notifications/initialized"))

        if (result.getAsJsonObject("capabilities")?.has("tools") != true) {
            LOG.info("MCP server '${config.name}' advertises no tools capability")
            return emptyList()
        }
        return listTools(opened, timeout)
    }

    fun callTool(toolName: String, arguments: JsonObject): String {
        val open = transport ?: throw McpException("not connected")
        val request = McpRpc.request(nextId.getAndIncrement(), "tools/call", JsonObject().apply {
            addProperty("name", toolName)
            add("arguments", arguments)
        })
        val result = McpRpc.resultOf(open.request(request, config.timeoutSeconds * 1000L), "tools/call")
        return renderResult(result)
    }

    override fun close() {
        runCatching { transport?.close() }
        transport = null
    }

    /** Follows `nextCursor` to the end: a server with many tools returns them a page at a time. */
    private fun listTools(open: McpTransport, timeout: Long): List<McpToolDescriptor> {
        val tools = mutableListOf<McpToolDescriptor>()
        var cursor: String? = null

        do {
            val params = JsonObject().apply { cursor?.let { addProperty("cursor", it) } }
            val request = McpRpc.request(nextId.getAndIncrement(), "tools/list", params)
            val result = McpRpc.resultOf(open.request(request, timeout), "tools/list")

            for (element in result.getAsJsonArray("tools").orEmpty()) {
                val tool = element as? JsonObject ?: continue
                val name = tool.get("name")?.asString ?: continue
                tools += McpToolDescriptor(
                    name = name,
                    description = tool.get("description")?.asString.orEmpty(),
                    // A tool with no schema still takes no arguments rather than any arguments, so
                    // the empty object is the honest translation and keeps the API from rejecting it.
                    inputSchema = tool.getAsJsonObject("inputSchema") ?: emptySchema(),
                )
            }

            cursor = result.get("nextCursor")?.asString
        } while (cursor != null && tools.size < 500)

        return tools
    }

    private fun emptySchema(): JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject())
    }
}
