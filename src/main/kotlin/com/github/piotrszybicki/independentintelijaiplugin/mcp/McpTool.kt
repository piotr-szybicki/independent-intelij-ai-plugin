package com.github.piotrszybicki.independentintelijaiplugin.mcp

import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool

class McpTool(
    private val project: Project,
    private val server: McpServerConfig,
    private val client: McpClient,
    private val descriptor: McpToolDescriptor,
    private val approvals: McpApprovalGate,
    override val name: String,
) : AICodingAgentTool {

    companion object {
        private val LOG = Logger.getInstance(McpTool::class.java)

        private val ILLEGAL_IN_NAME = Regex("[^a-zA-Z0-9_-]")
        private const val MAX_NAME_LENGTH = 128

        fun qualifiedName(server: String, tool: String): String =
            "mcp__${sanitize(server)}__${sanitize(tool)}".take(MAX_NAME_LENGTH)

        private fun sanitize(value: String) = ILLEGAL_IN_NAME.replace(value, "_")
    }

    override val interruptible = true

    override val description: String = buildString {
        append("(via the MCP server \"${server.name}\") ")
        val given = descriptor.description.trim()
        append(given.ifEmpty { "No description was provided by the server." })
    }

    override val inputSchema: JsonObject = descriptor.inputSchema.deepCopy().apply {
        if (!has("type")) addProperty("type", "object")
        if (!has("properties")) add("properties", JsonObject())
    }

    override fun execute(input: JsonObject): String {
        if (!approvals.confirm(project, name, server, input)) {
            // A refusal is a normal outcome, not a failure: reported so the model adapts rather
            // than spending another turn on the same call.
            return "The user declined this MCP tool call. Do not call it again; ask what to do instead."
        }

        return try {
            client.callTool(descriptor.name, input)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            "The call to \"${descriptor.name}\" was interrupted."
        } catch (e: Exception) {
            LOG.info("MCP tool '$name' failed: ${e.message}")
            "Error: the MCP server \"${server.name}\" could not run \"${descriptor.name}\": ${e.message}"
        }
    }
}
