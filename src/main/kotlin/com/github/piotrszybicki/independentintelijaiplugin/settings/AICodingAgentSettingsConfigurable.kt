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
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.github.piotrszybicki.independentintelijaiplugin.mcp.McpConfigException
import com.github.piotrszybicki.independentintelijaiplugin.mcp.McpServerConfig
import com.github.piotrszybicki.independentintelijaiplugin.mcp.McpService
import com.github.piotrszybicki.independentintelijaiplugin.skills.SkillCatalog
import com.github.piotrszybicki.independentintelijaiplugin.skills.SkillRoot
import com.github.piotrszybicki.independentintelijaiplugin.tools.ToolCatalog
import com.github.piotrszybicki.independentintelijaiplugin.tools.ToolCategory
import java.io.File
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent

class AICodingAgentSettingsConfigurable : Configurable {

    private val apiKeyStatusLabel = JBLabel()
    private val modelField = JBTextField()
    private val maxTokensField = JBTextField()
    private val maxIterationsField = JBTextField()
    private val endpointField = JBTextField()
    private val apiVersionField = JBTextField()

    private val authSchemeCombo = ComboBox(DefaultComboBoxModel(AuthScheme.entries.toTypedArray())).apply {
        renderer = SimpleListCellRenderer.create { label, scheme, _ -> label.text = scheme?.displayName.orEmpty() }
    }

    private val protocolCombo = ComboBox(DefaultComboBoxModel(WireProtocol.entries.toTypedArray())).apply {
        renderer = SimpleListCellRenderer.create { label, protocol, _ -> label.text = protocol?.displayName.orEmpty() }
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

    /**
     * The tools picked in [ToolSelectionDialog] but not yet applied.
     *
     * Held here rather than read back off a component, because the component the user chose them
     * with is gone by the time [apply] runs -- the dialog closes with its checkboxes.
     */
    private var pendingTools: Set<String> = emptySet()

    private val toolsSummaryLabel = JBLabel()

    private val skillPathsArea = JBTextArea(5, 40).apply {
        lineWrap = false
        font = JBUI.Fonts.create("Monospaced", font.size)
    }

    override fun getDisplayName(): String = "AICodingAgent"

    override fun createComponent(): JComponent = panel {
        group("Connection") {
            row("API protocol:") {
                cell(protocolCombo).align(AlignX.FILL)
            }.rowComment(
                "Which API the endpoint speaks. The request body differs between them, not just the " +
                    "headers &mdash; a Foundry or Azure deployment answers a Messages-API request " +
                    "with <i>Unsupported parameter: 'messages'</i>, which is this setting rather " +
                    "than anything wrong with the request. Pick the one matching the URL's path: " +
                    "<code>/messages</code>, <code>/chat/completions</code> or <code>/responses</code>.",
            )
            row("Endpoint URL:") {
                cell(endpointField).align(AlignX.FILL)
            }.rowComment(
                "The full endpoint, path included. Anthropic's own is " +
                    "<code>${AICodingAgentSettingsState.DEFAULT_ENDPOINT_URL}</code>; an Azure or " +
                    "Foundry one looks like " +
                    "<code>https://&lt;resource&gt;.services.ai.azure.com/openai/v1/responses</code>. " +
                    "Point this at a gateway or proxy to route through it.",
            )
            row("API token:") {
                cell(apiKeyStatusLabel).align(AlignX.FILL)
            }.rowComment(
                "Read from the <code>${AICodingAgentCredentials.ENV_VAR}</code> environment variable. " +
                    "The IDE only sees variables set when it was launched, so set it and then " +
                    "restart the IDE.",
            )
            row("Send token as:") {
                cell(authSchemeCombo).align(AlignX.FILL)
            }
            row("anthropic-version:") {
                cell(apiVersionField).align(AlignX.FILL)
            }.rowComment(
                "Leave empty to omit the header, which some gateways require. Sent only when the " +
                    "protocol above is the Messages API &mdash; no other provider knows it.",
            )
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
            row("Max tokens:") {
                cell(maxTokensField).columns(10)
            }.rowComment(
                "The longest single reply the model may write. Past it the answer is cut off " +
                    "mid-sentence and you are asked whether to spend another request continuing it " +
                    "&mdash; saying yes doubles the limit for the rest of that chat, so this is " +
                    "where a conversation starts rather than a fixed ceiling.",
            )
            row("Tool calls per message:") {
                cell(maxIterationsField).columns(10)
            }.rowComment(
                "How many rounds of tool calls one message gets before the assistant stops and asks " +
                    "whether to keep going. Answering yes buys another such run, so this is not a " +
                    "ceiling &mdash; it is the check that stops a loop running away unnoticed.",
            )
        }
        group("Tools") {
            row {
                cell(toolsSummaryLabel).align(AlignX.FILL)
            }
            row {
                button("Select Tools…") { chooseTools() }
            }.rowComment(
                "Which built-in tools the model is told about. Every definition is re-sent with " +
                    "every message, so the whole set costs tokens on each turn whether it gets " +
                    "used or not &mdash; the default is what it takes to read and navigate a " +
                    "project, and nothing that changes it. Switch on the rest when you want the " +
                    "model editing, running or debugging. A tool that is off cannot be called, so " +
                    "this is a limit on what the model can do and not only on what it costs. " +
                    "Chosen here, saved with the rest of this page, and in effect from the next " +
                    "message.",
            )
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
        group("Skills") {
            row {
                scrollCell(skillPathsArea).align(AlignX.FILL)
            }.rowComment(
                "Directories to look for skills in, one per line. A skill is a " +
                    "<code>SKILL.md</code> in its own folder below one of these, and its " +
                    "<code>name</code> and <code>description</code> are added to every request so " +
                    "the model knows the skill exists &mdash; the instructions in it are only read " +
                    "when it is actually used.<br/>" +
                    "Relative paths are resolved against the project root, absolute ones are not, " +
                    "so a directory outside the project works. <code>~</code> is your home " +
                    "directory and <code>\${env:NAME}</code> reads a variable. Earlier lines win " +
                    "when two skills share a name. Takes effect on the next message.",
            )
            row {
                button("Scan for Skills") { scanSkills() }
            }
        }
    }.also { reset() }

    override fun isModified(): Boolean {
        val settings = AICodingAgentSettingsState.getInstance().state
        return modelField.text != settings.model ||
            maxTokensField.positiveIntOr(settings.maxTokens) != settings.maxTokens ||
            maxIterationsField.positiveIntOr(settings.maxIterations) != settings.maxIterations ||
            endpointField.text != settings.endpointUrl ||
            apiVersionField.text != settings.apiVersion ||
            extraHeadersArea.text != settings.extraHeaders ||
            mcpServersArea.text != settings.mcpServers ||
            confirmMcpCheckBox.isSelected != settings.confirmMcpToolCalls ||
            skillPathsArea.text != settings.skillPaths ||
            pendingTools != ToolCatalog.parse(settings.enabledTools) ||
            authSchemeCombo.selectedItem != settings.authScheme ||
            protocolCombo.selectedItem != settings.wireProtocol
    }

    override fun apply() {
        val settings = AICodingAgentSettingsState.getInstance().state
        settings.model = modelField.text.trim().ifBlank { "claude-sonnet-5" }
        settings.maxTokens = maxTokensField.positiveIntOr(settings.maxTokens)
        settings.maxIterations = maxIterationsField.positiveIntOr(settings.maxIterations)
        // Put the accepted numbers back, so a field that was left with something unusable in it
        // shows what actually got saved rather than the text that was ignored.
        maxTokensField.text = settings.maxTokens.toString()
        maxIterationsField.text = settings.maxIterations.toString()
        // Blanking the endpoint means "back to Anthropic" rather than an unusable configuration.
        settings.endpointUrl = endpointField.text.trim()
            .ifBlank { AICodingAgentSettingsState.DEFAULT_ENDPOINT_URL }
        settings.apiVersion = apiVersionField.text.trim()
        settings.extraHeaders = extraHeadersArea.text
        settings.authScheme = authSchemeCombo.selectedItem as? AuthScheme ?: AuthScheme.X_API_KEY
        settings.wireProtocol = protocolCombo.selectedItem as? WireProtocol ?: WireProtocol.ANTHROPIC_MESSAGES
        // Nothing to notify: the roots are rescanned on the next turn, so a path added here is read
        // the next time the user sends a message.
        settings.skillPaths = skillPathsArea.text
        // Same again: the chat panel holds every built-in tool and asks the catalog which of them to
        // send each turn, so this lands on the next message without rebuilding anything.
        settings.enabledTools = ToolCatalog.format(pendingTools)

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
        val settings = AICodingAgentSettingsState.getInstance().state
        apiKeyStatusLabel.text = if (AICodingAgentCredentials.apiKey == null) {
            "Not set -- ${AICodingAgentCredentials.ENV_VAR} is empty or undefined in the IDE's environment."
        } else {
            "Set from ${AICodingAgentCredentials.ENV_VAR}."
        }
        modelField.text = settings.model
        maxTokensField.text = settings.maxTokens.toString()
        maxIterationsField.text = settings.maxIterations.toString()
        endpointField.text = settings.endpointUrl
        apiVersionField.text = settings.apiVersion
        extraHeadersArea.text = settings.extraHeaders
        authSchemeCombo.selectedItem = settings.authScheme
        protocolCombo.selectedItem = settings.wireProtocol
        mcpServersArea.text = settings.mcpServers
        confirmMcpCheckBox.isSelected = settings.confirmMcpToolCalls
        skillPathsArea.text = settings.skillPaths
        pendingTools = ToolCatalog.parse(settings.enabledTools)
        updateToolsSummary()
    }

    /**
     * Opens the picker on what is pending rather than on what is saved, so cancelling this page
     * after choosing tools discards them along with every other unapplied edit.
     */
    private fun chooseTools() {
        val dialog = ToolSelectionDialog(ProjectManager.getInstance().openProjects.firstOrNull(), pendingTools)
        if (dialog.showAndGet()) {
            pendingTools = dialog.selectedTools
            updateToolsSummary()
        }
    }

    /** Per category rather than a bare total: which corners are switched off is the useful part. */
    private fun updateToolsSummary() {
        val byCategory = ToolCategory.entries.joinToString(", ") { category ->
            val inCategory = ToolCatalog.entries.filter { it.category == category }
            "${category.displayName} ${inCategory.count { it.name in pendingTools }}/${inCategory.size}"
        }
        toolsSummaryLabel.text =
            "${pendingTools.size} of ${ToolCatalog.entries.size} tools selected -- $byCategory"
    }

    /**
     * The number typed into a field, or [fallback] when it is not a usable one. Both numbers here
     * are budgets the code counts down, so anything but a positive integer -- empty, a word, a zero
     * -- is treated as "leave it alone" rather than saved and acted on.
     */
    private fun JBTextField.positiveIntOr(fallback: Int): Int =
        text.trim().toIntOrNull()?.takeIf { it > 0 } ?: fallback

    /**
     * Scans the directories in the field and reports what was found in each.
     *
     * Like [testServers], it runs against the typed text rather than the saved settings -- a path
     * outside the project is easy to get slightly wrong, and finding that out here beats finding it
     * out as a skill that silently never gets used.
     */
    private fun scanSkills() {
        val project: Project? = ProjectManager.getInstance().openProjects.firstOrNull()
        val roots = SkillRoot.parseAll(skillPathsArea.text, project?.basePath?.let(::File))
        if (roots.isEmpty()) {
            Messages.showInfoMessage("No skill directories are configured.", "Skills")
            return
        }

        val scan = SkillCatalog.scan(roots)
        val report = buildString {
            for (status in scan.statuses) {
                val detail = when {
                    status.error != null -> "${status.error} (${status.resolved.ifBlank { "unresolved" }})"
                    status.skillCount == 0 -> "no skills in ${status.resolved}"
                    else -> "${status.skillCount} skill(s) in ${status.resolved}"
                }
                appendLine("${status.configured}: $detail")
            }
            if (scan.skills.isNotEmpty()) {
                appendLine()
                appendLine("Found: " + scan.skills.joinToString(", ") { it.name })
            }
        }
        Messages.showInfoMessage(report.trim(), "Skills")
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
