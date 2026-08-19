package com.github.piotrszybicki.independentintelijaiplugin.mcp

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

data class McpToolDescriptor(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

class McpClient(private val config: McpServerConfig, private val workingDir: File?) : AutoCloseable {

    companion object {
        private val LOG = Logger.getInstance(McpClient::class.java)
        private val GSON = Gson()

        private const val PROTOCOL_VERSION = "2025-06-18"

        private const val MAX_RESULT_CHARS = 30_000

        fun renderResult(result: JsonObject): String {
            val rendered = result.getAsJsonArray("content").orEmpty()
                .mapNotNull { block -> (block as? JsonObject)?.let(::renderBlock) }
            val structured = result.getAsJsonObject("structuredContent")

            var text = rendered.joinToString("\n\n").trim()
            if (text.isEmpty() && structured != null) text = GSON.toJson(structured)
            if (text.isEmpty()) text = "(the tool returned no content)"

            if (text.length > MAX_RESULT_CHARS) {
                text = text.take(MAX_RESULT_CHARS) +
                    "\n\n[TRUNCATED: ${text.length - MAX_RESULT_CHARS} more characters omitted]"
            }

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

    var serverInfo: String = config.name
        private set

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
