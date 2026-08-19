package com.github.piotrszybicki.independentintelijaiplugin.mcp

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool
import com.github.piotrszybicki.independentintelijaiplugin.settings.AICodingAgentSettingsState
import java.io.File

@Service(Service.Level.PROJECT)
class McpService(private val project: Project) : Disposable {

    data class ServerStatus(val name: String, val target: String, val toolCount: Int, val error: String?)

    private val approvals = McpApprovalGate()

    private class Connection(val client: McpClient?, val tools: List<AICodingAgentTool>, val status: ServerStatus)

    private var connections: List<Connection>? = null
    private var builtFrom: String? = null

    @Synchronized
    fun tools(): List<AICodingAgentTool> {
        val all = current().flatMap { it.tools }
        val enabled = AICodingAgentSettingsState.getInstance().state.enabledMcpTools
        if (enabled.isBlank()) return all
        val enabledSet = enabled.split(",").mapTo(mutableSetOf()) { it.trim() }
        return all.filter { it.name in enabledSet }
    }

    @Synchronized
    fun statuses(): List<ServerStatus> = current().map { it.status }

    @Synchronized
    fun reload() {
        connections?.forEach { runCatching { it.client?.close() } }
        connections = null
        builtFrom = null
    }

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

        data class ProbeToolsResult(val serverName: String, val tools: List<McpToolDescriptor>, val error: String?)

        fun probeTools(project: Project?, configs: List<McpServerConfig>): List<ProbeToolsResult> {
            val workingDir = project?.basePath?.let(::File)
            return configs.filter { it.enabled }.map { config ->
                val client = McpClient(config, workingDir)
                try {
                    val tools = client.connect()
                    ProbeToolsResult(config.name, tools, null)
                } catch (e: Exception) {
                    ProbeToolsResult(config.name, emptyList(), e.message ?: e.toString())
                } finally {
                    runCatching { client.close() }
                }
            }
        }

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
