package com.github.piotrszybicki.independentintelijaiplugin.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.github.piotrszybicki.independentintelijaiplugin.mcp.McpConfigException
import com.github.piotrszybicki.independentintelijaiplugin.mcp.McpServerConfig
import com.github.piotrszybicki.independentintelijaiplugin.mcp.McpService
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent

class AnthropicSettingsConfigurable : Configurable {

    private val apiKeyStatusLabel = JBLabel()
    private val modelField = JBTextField()
    private val endpointField = JBTextField()
    private val anthropicVersionField = JBTextField()

    private val authSchemeCombo = ComboBox(DefaultComboBoxModel(AuthScheme.entries.toTypedArray())).apply {
        renderer = SimpleListCellRenderer.create("") { scheme -> scheme.displayName }
    }

    private val extraHeadersArea = JBTextArea(4, 40).apply {
        lineWrap = false
        font = JBUI.Fonts.create("Monospaced", font.size)
    }

    private val mcpServersArea = JBTextArea(10, 40).apply {
        lineWrap = false
        font = JBUI.Fonts.create("Monospaced", font.size)
    }

    private val confirmMcpCheckBox = JBCheckBox("Ask before each MCP tool call")

    override fun getDisplayName(): String = "Anthropic Chat"

    override fun createComponent(): JComponent = panel {
        group("Connection") {
            row("Endpoint URL:") {
                cell(endpointField).align(AlignX.FILL)
            }.rowComment(
                "The full messages endpoint, path included. Anthropic's own is " +
                    "<code>${AnthropicSettingsState.DEFAULT_ENDPOINT_URL}</code>. Point this at a " +
                    "gateway or proxy to route through it, as long as it speaks the Messages API.",
            )
            row("API token:") {
                cell(apiKeyStatusLabel).align(AlignX.FILL)
            }.rowComment(
                "Read from the <code>${AnthropicCredentials.ENV_VAR}</code> environment variable. " +
                    "The IDE only sees variables set when it was launched, so set it and then " +
                    "restart the IDE.",
            )
            row("Send token as:") {
                cell(authSchemeCombo).align(AlignX.FILL)
            }
            row("anthropic-version:") {
                cell(anthropicVersionField).align(AlignX.FILL)
            }.rowComment("Leave empty to omit the header, which some gateways require.")
            row("Extra headers:") {
                cell(extraHeadersArea).align(AlignX.FILL)
            }.rowComment(
                "One <code>Name: Value</code> per line, for routing or tenancy headers a gateway " +
                    "needs. Stored in plain text &mdash; keep secrets in the environment instead.",
            )
        }
        group("Model") {
            row("Model:") {
                cell(modelField).align(AlignX.FILL)
            }
        }
        group("MCP servers") {
            row {
                scrollCell(mcpServersArea).align(AlignX.FILL)
            }.rowComment(
                "The <code>mcpServers</code> JSON the other MCP clients use, so an entry can be " +
                    "pasted from a server's own README. A <code>command</code> starts a local " +
                    "process; a <code>url</code> reaches a remote server over Streamable HTTP. " +
                    "Write <code>\${env:NAME}</code> for anything secret &mdash; this field is " +
                    "stored in plain text. Takes effect on the next message.<br/>" +
                    "<code>{\"mcpServers\": {\"deepwiki\": {\"url\": \"https://mcp.deepwiki.com/mcp\"}}}</code>",
            )
            row {
                cell(confirmMcpCheckBox)
            }.rowComment(
                "MCP servers are not part of this plugin: a local one runs with your account's " +
                    "permissions, a remote one receives whatever the model passes it. Turning this " +
                    "off removes the only check on either.",
            )
            row {
                button("Test Servers") { testServers() }
            }
        }
    }.also { reset() }

    override fun isModified(): Boolean {
        val settings = AnthropicSettingsState.getInstance().state
        return modelField.text != settings.model ||
            endpointField.text != settings.endpointUrl ||
            anthropicVersionField.text != settings.anthropicVersion ||
            extraHeadersArea.text != settings.extraHeaders ||
            mcpServersArea.text != settings.mcpServers ||
            confirmMcpCheckBox.isSelected != settings.confirmMcpToolCalls ||
            authSchemeCombo.selectedItem != settings.authScheme
    }

    override fun apply() {
        val settings = AnthropicSettingsState.getInstance().state
        settings.model = modelField.text.trim().ifBlank { "claude-sonnet-5" }
        // Blanking the endpoint means "back to Anthropic" rather than an unusable configuration.
        settings.endpointUrl = endpointField.text.trim()
            .ifBlank { AnthropicSettingsState.DEFAULT_ENDPOINT_URL }
        settings.anthropicVersion = anthropicVersionField.text.trim()
        settings.extraHeaders = extraHeadersArea.text
        settings.authScheme = authSchemeCombo.selectedItem as? AuthScheme ?: AuthScheme.X_API_KEY

        val serversChanged = settings.mcpServers != mcpServersArea.text
        settings.mcpServers = mcpServersArea.text
        settings.confirmMcpToolCalls = confirmMcpCheckBox.isSelected
        // The service reconnects on its own when the text changes, but only on the next turn --
        // dropping the old processes here means a server removed from the list stops running now.
        if (serversChanged) {
            ProjectManager.getInstance().openProjects.forEach { McpService.getInstance(it).reload() }
        }
    }

    override fun reset() {
        val settings = AnthropicSettingsState.getInstance().state
        apiKeyStatusLabel.text = if (AnthropicCredentials.apiKey == null) {
            "Not set -- ${AnthropicCredentials.ENV_VAR} is empty or undefined in the IDE's environment."
        } else {
            "Set from ${AnthropicCredentials.ENV_VAR}."
        }
        modelField.text = settings.model
        endpointField.text = settings.endpointUrl
        anthropicVersionField.text = settings.anthropicVersion
        extraHeadersArea.text = settings.extraHeaders
        authSchemeCombo.selectedItem = settings.authScheme
        mcpServersArea.text = settings.mcpServers
        confirmMcpCheckBox.isSelected = settings.confirmMcpToolCalls
    }

    /**
     * Connects to each configured server once and reports what it offered.
     *
     * Runs against the text in the field rather than the saved settings: the point of the button is
     * to find out whether an entry works before committing to it. The connections it opens are its
     * own and are closed again, so it never disturbs a conversation in progress.
     */
    private fun testServers() {
        val configs = try {
            McpServerConfig.parseAll(mcpServersArea.text)
        } catch (e: McpConfigException) {
            Messages.showErrorDialog("The MCP configuration is ${e.message}", "MCP Servers")
            return
        }
        if (configs.isEmpty()) {
            Messages.showInfoMessage("No MCP servers are configured.", "MCP Servers")
            return
        }

        val project: Project? = ProjectManager.getInstance().openProjects.firstOrNull()
        // Modal rather than background: connecting starts processes and can take a while, and the
        // result only makes sense next to the field it was read from.
        val task = object : Task.Modal(project, "Connecting to MCP Servers", true) {
            var report = ""

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                report = McpService.probe(project, configs).joinToString("\n") { status ->
                    val detail = when {
                        status.error != null -> "FAILED -- ${status.error}"
                        status.toolCount == 0 -> "connected, but offers no tools"
                        else -> "connected, ${status.toolCount} tool(s)"
                    }
                    "${status.name}: $detail"
                }
            }

            override fun onSuccess() = Messages.showInfoMessage(report, "MCP Servers")
        }
        ProgressManager.getInstance().run(task)
    }
}
