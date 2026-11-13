package com.github.piotrszybicki.independentintelijaiplugin.settings

import com.github.piotrszybicki.independentintelijaiplugin.logging.ModelUsageDatabase
import com.github.piotrszybicki.independentintelijaiplugin.mcp.McpService
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.github.piotrszybicki.independentintelijaiplugin.toolWindow.ChatToolWindowFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import javax.swing.JComponent

class AICodingAgentSettingsConfigurable : Configurable {
    private val configurationStatus = JBLabel()

    private val configurationText =
        JBTextArea(24, 80).apply {
            isEditable = false
            lineWrap = false
            font = JBUI.Fonts.create("Monospaced", font.size)
        }

    private var loadedDatabase = AgentConfigurations.LoadedDatabase(UsageDatabaseConfig.OFF, null)

    override fun getDisplayName(): String = "AICodingAgent"

    override fun createComponent(): JComponent =
        panel {
            row {
                button("Add MCP Server") { addMcpServer() }
                button("Reload Configuration") { reloadConfiguration() }.component.name =
                    "settings.reloadConfigurationButton"
                button("Test MySQL Connection") { testUsageDatabase() }.component.name =
                    "settings.testMySqlButton"
            }
            row { cell(configurationStatus).align(AlignX.FILL) }
            row { scrollCell(configurationText).align(AlignX.FILL) }
        }.also {
            it.name = "settings.root"
            reloadConfiguration()
        }

    override fun isModified(): Boolean = false

    override fun apply() = Unit

    override fun reset() = reloadConfiguration()

    private fun reloadConfiguration() {
        val project = project()
        if (project == null) {
            configurationText.text = ""
            showProblem("Open a project to view its configuration.")
            return
        }

        val configurations = AgentConfigurations.getInstance(project)
        configurationText.text = configurations.text().orEmpty()
        val problem = configurations.unavailableReason
        if (problem != null) {
            showProblem(problem)
        } else {
            configurationStatus.text = configurations.path.toString()
            configurationStatus.foreground = UIUtil.getLabelForeground()
        }

        val previous = loadedDatabase.database
        loadedDatabase = configurations.usageDatabase()
        if (previous != loadedDatabase.database) ModelUsageDatabase.close()
        ChatToolWindowFactory.refreshProviderBars()
    }

    private fun addMcpServer() {
        val project = project() ?: return
        val configurations = AgentConfigurations.getInstance(project)
        var resolvedFile: com.intellij.openapi.vfs.VirtualFile? = null
        WriteIntentReadAction.run { resolvedFile = configurations.virtualFile() }
        val file = resolvedFile ?: run {
            Messages.showErrorDialog("The configuration file is unavailable.", "Add MCP Server")
            return
        }
        val dialog = AddMcpServerDialog()
        if (!dialog.showAndGet()) return
        val server = dialog.configuration()
        try {
            WriteIntentReadAction.run {
            val document = FileDocumentManager.getInstance().getDocument(file)
                ?: error("Cannot open the configuration file for editing.")
            val root = JsonParser.parseString(document.text).asJsonObject
            val existing = root.get("mcpServers")
            require(existing == null || existing.isJsonObject) { "mcpServers must be an object." }
            val servers = existing?.asJsonObject ?: JsonObject().also { root.add("mcpServers", it) }
            require(!servers.has(server.name)) { "A server with this name already exists." }
            servers.add(server.name, JsonObject().apply {
                addProperty("url", server.url)
                if (server.headers.isNotEmpty()) add("headers", JsonObject().apply {
                    server.headers.forEach { (key, value) -> addProperty(key, value) }
                })
            })
            val updated = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root) + "\n"
            WriteCommandAction.runWriteCommandAction(project) { document.setText(updated) }
            FileDocumentManager.getInstance().saveDocument(document)
            }
            reloadConfiguration()
        } catch (e: Exception) {
            Messages.showErrorDialog(e.message ?: e.toString(), "Add MCP Server")
            return
        }
        ProgressManager.getInstance().run(object : Task.Modal(project, "Discovering MCP Tools", false) {
            private var result: McpService.Companion.ProbeToolsResult? = null
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                result = McpService.probeTools(project, listOf(server)).first()
            }
            override fun onSuccess() {
                val found = result ?: return
                if (found.error != null) {
                    Messages.showErrorDialog("Server saved, but tool discovery failed: " + found.error, "MCP Server")
                } else {
                    try {
                        WriteIntentReadAction.run {
                        val document = FileDocumentManager.getInstance().getDocument(file)
                            ?: error("Cannot open the configuration file for editing.")
                        val root = JsonParser.parseString(document.text).asJsonObject
                        val entry = root.getAsJsonObject("mcpServers")?.getAsJsonObject(server.name)
                            ?: error("The saved server was removed during discovery.")
                        require(entry.get("url")?.asString == server.url) {
                            "The server address changed during discovery."
                        }
                        entry.add("tools", JsonArray().apply { found.tools.forEach { add(it.name) } })
                        val updated = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root) + "\n"
                        WriteCommandAction.runWriteCommandAction(project) { document.setText(updated) }
                        FileDocumentManager.getInstance().saveDocument(document)
                        }
                        reloadConfiguration()
                        Messages.showInfoMessage("Server saved. Discovered ${found.tools.size} tool(s):\n" +
                            found.tools.joinToString("\n") { it.name }, "MCP Server")
                    } catch (e: Exception) {
                        Messages.showErrorDialog("Could not save discovered tools: ${e.message}", "MCP Server")
                    }
                }
            }
        })
    }

    private fun showProblem(message: String) {
        configurationStatus.text = message
        configurationStatus.foreground = JBColor.namedColor("Label.errorForeground", JBColor.RED)
    }

    private fun testUsageDatabase() {
        reloadConfiguration()
        loadedDatabase.error?.let {
            Messages.showErrorDialog(it, "MySQL Connection")
            return
        }
        val url = loadedDatabase.database.url
        if (url.isBlank()) {
            Messages.showInfoMessage(
                "No database URL is configured in ${AgentConfiguration.FILE_NAME}.",
                "MySQL Connection",
            )
            return
        }

        val task =
            object : Task.Modal(project(), "Connecting to MySQL", true) {
                var report = ""

                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    report = ModelUsageDatabase.test(url)
                }

                override fun onSuccess() = Messages.showInfoMessage(report, "MySQL Connection")
            }
        ProgressManager.getInstance().run(task)
    }

    private fun project(): Project? = ProjectManager.getInstance().openProjects.firstOrNull()
}
