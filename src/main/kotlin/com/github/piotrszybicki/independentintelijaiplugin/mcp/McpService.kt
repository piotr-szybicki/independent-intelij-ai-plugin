package com.github.piotrszybicki.independentintelijaiplugin.mcp

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool
import com.github.piotrszybicki.independentintelijaiplugin.settings.AICodingAgentSettingsState
import java.io.File

/**
 * Owns the connections to the configured MCP servers and the tools they expose.
 *
 * Connecting is lazy and cached: it spawns processes and does network I/O, which must not happen
 * while the tool window is being built, and which would be wasteful to repeat every turn. The cache
 * is keyed on the configuration text, so editing the settings takes effect on the next turn without
 * anything having to notify anything.
 *
 * Nothing here throws. A server that will not start is a server whose tools are missing, not a
 * broken chat -- so failures are recorded against the server that caused them and the rest carry
 * on. [statuses] is where those failures become visible.
 */
@Service(Service.Level.PROJECT)
class McpService(private val project: Project) : Disposable {

    data class ServerStatus(val name: String, val target: String, val toolCount: Int, val error: String?)

    private val approvals = McpApprovalGate()

    private class Connection(val client: McpClient?, val tools: List<AICodingAgentTool>, val status: ServerStatus)

    /** Null until the first connect; keyed on the settings text it was built from. */
    private var connections: List<Connection>? = null
    private var builtFrom: String? = null

    /**
     * The tools to offer this turn. Called from the agent's pooled thread, never the EDT.
     *
     * The list is rebuilt only when the configuration has changed, so the cost of a turn is the
     * same whether or not any servers are configured.
     */
    @Synchronized
    fun tools(): List<AICodingAgentTool> = current().flatMap { it.tools }

    /** One line per configured server, for the settings panel and for diagnosing a missing tool. */
    @Synchronized
    fun statuses(): List<ServerStatus> = current().map { it.status }

    /** Drops every connection so the next turn builds them again. */
    @Synchronized
    fun reload() {
        connections?.forEach { runCatching { it.client?.close() } }
        connections = null
        builtFrom = null
    }

    /** Called when a new chat starts: a blanket approval is given for a conversation, not forever. */
    fun forgetApprovals() = approvals.forget()

    @Synchronized
    override fun dispose() = reload()

    private fun current(): List<Connection> {
        val text = AICodingAgentSettingsState.getInstance().state.mcpServers
        connections?.let { if (builtFrom == text) return it }

        connections?.forEach { runCatching { it.client?.close() } }

        val built = connect(text)
        connections = built
        builtFrom = text
        return built
    }

    private fun connect(text: String): List<Connection> {
        val configs = try {
            McpServerConfig.parseAll(text)
        } catch (e: McpConfigException) {
            LOG.info("MCP configuration is unusable: ${e.message}")
            return listOf(
                Connection(null, emptyList(), ServerStatus("(configuration)", "", 0, e.message)),
            )
        }

        val workingDir = project.basePath?.let(::File)
        val usedNames = mutableSetOf<String>()

        return configs.filter { it.enabled }.map { config ->
            val client = McpClient(config, workingDir)
            try {
                val tools = client.connect().map { descriptor ->
                    McpTool(project, config, client, descriptor, approvals, uniqueName(usedNames, config, descriptor))
                }
                LOG.info("MCP server '${config.name}' (${client.serverInfo}) offers ${tools.size} tools")
                Connection(client, tools, ServerStatus(config.name, config.target, tools.size, null))
            } catch (e: Exception) {
                LOG.info("MCP server '${config.name}' did not connect: ${e.message}")
                runCatching { client.close() }
                Connection(null, emptyList(), ServerStatus(config.name, config.target, 0, e.message ?: e.toString()))
            }
        }
    }

    /**
     * Two servers whose names differ only in characters the API forbids would qualify down to the
     * same tool name, and the agent looks tools up by name -- so one would silently answer for the
     * other. Rare, but the fix is one line.
     */
    private fun uniqueName(used: MutableSet<String>, config: McpServerConfig, tool: McpToolDescriptor): String {
        val base = McpTool.qualifiedName(config.name, tool.name)
        var name = base
        var suffix = 2
        while (!used.add(name)) {
            name = base.take(124) + "_" + suffix++
        }
        return name
    }

    companion object {
        private val LOG = Logger.getInstance(McpService::class.java)

        fun getInstance(project: Project): McpService = project.getService(McpService::class.java)

        /**
         * Connects to [configs] once and reports what happened, without touching the live
         * connections. This is what the settings panel's Test button runs, so it has to work on
         * text the user has typed but not yet applied.
         */
        fun probe(project: Project?, configs: List<McpServerConfig>): List<ServerStatus> {
            val workingDir = project?.basePath?.let(::File)
            return configs.map { config ->
                if (!config.enabled) return@map ServerStatus(config.name, config.target, 0, "disabled")
                val client = McpClient(config, workingDir)
                try {
                    val tools = client.connect()
                    ServerStatus(config.name, config.target, tools.size, null)
                } catch (e: Exception) {
                    ServerStatus(config.name, config.target, 0, e.message ?: e.toString())
                } finally {
                    runCatching { client.close() }
                }
            }
        }
    }
}
