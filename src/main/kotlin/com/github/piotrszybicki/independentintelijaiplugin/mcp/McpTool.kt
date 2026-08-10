package com.github.piotrszybicki.independentintelijaiplugin.mcp

import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool

/**
 * Presents one tool from an MCP server as an ordinary [AICodingAgentTool].
 *
 * This is the whole of the adaptation, and it is small because the two models line up: MCP's
 * name/description/inputSchema is the Messages API's tool definition, and `tools/call` is
 * [execute]. Everything above this -- the agent loop, the transcript, the change session -- treats
 * an MCP tool exactly like a built-in one and does not know the difference.
 */
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

        /** What the Messages API accepts as a tool name. */
        private val ILLEGAL_IN_NAME = Regex("[^a-zA-Z0-9_-]")
        private const val MAX_NAME_LENGTH = 128

        /**
         * Namespaces a server's tool so it cannot collide with a built-in one.
         *
         * Two servers commonly offer a `search` or a `fetch`, and one of them shadowing
         * `find_in_files` would be worse still. The `mcp__server__tool` shape is what the other
         * clients use, which also makes it obvious in the transcript where a call is going.
         */
        fun qualifiedName(server: String, tool: String): String =
            "mcp__${sanitize(server)}__${sanitize(tool)}".take(MAX_NAME_LENGTH)

        private fun sanitize(value: String) = ILLEGAL_IN_NAME.replace(value, "_")
    }

    /**
     * Stopping the turn releases this thread, but the server on the other side is not this
     * plugin's process and finishes whatever it started. That is the same bargain as the timeout,
     * and leaving the Stop button available is worth more than the distinction: without it a wedged
     * server holds the chat for its whole timeout with nothing the user can do.
     */
    override val interruptible = true

    override val description: String = buildString {
        append("(via the MCP server \"${server.name}\") ")
        val given = descriptor.description.trim()
        append(given.ifEmpty { "No description was provided by the server." })
    }

    /**
     * Normalised rather than passed through: servers do leave `type` off a schema that is plainly
     * an object, and the API rejects the definition outright rather than ignoring the omission.
     */
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
