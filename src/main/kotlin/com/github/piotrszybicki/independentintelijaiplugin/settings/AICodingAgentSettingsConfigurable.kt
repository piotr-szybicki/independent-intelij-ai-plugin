package com.github.piotrszybicki.independentintelijaiplugin.settings

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentEndpoint
import com.github.piotrszybicki.independentintelijaiplugin.mcp.McpConfigException
import com.github.piotrszybicki.independentintelijaiplugin.mcp.McpServerConfig
import com.github.piotrszybicki.independentintelijaiplugin.mcp.McpService
import com.github.piotrszybicki.independentintelijaiplugin.skills.SkillCatalog
import com.github.piotrszybicki.independentintelijaiplugin.skills.SkillRoot
import com.github.piotrszybicki.independentintelijaiplugin.tools.ToolCatalog
import com.github.piotrszybicki.independentintelijaiplugin.tools.ToolCategory
import java.awt.event.ItemEvent
import java.io.File
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent

class AICodingAgentSettingsConfigurable : Configurable {

    /**
     * The dropdown the whole provider section reduces to.
     *
     * Holds the configurations themselves rather than their names, so the summary below it can be
     * drawn from the selection without going back to the file for it -- and so a file edited while
     * this page is open cannot leave the two disagreeing.
     */
    private val configurationCombo = ComboBox(DefaultComboBoxModel<AgentConfiguration>()).apply {
        renderer = SimpleListCellRenderer.create<AgentConfiguration> { label, value, _ ->
            label.text = value?.name.orEmpty()
        }
    }

    /** What the selected configuration says, so picking one does not mean opening the file to check. */
    private val configurationSummaryLabel = JBLabel().apply {
        setComponentStyle(UIUtil.ComponentStyle.SMALL)
        setFontColor(UIUtil.FontColor.BRIGHTER)
    }

    /**
     * Why the selection cannot be used, when it cannot -- an unreadable file, a missing token, a URL
     * whose path disagrees with the protocol. Here rather than left to the first message, because
     * all of it is knowable now and none of it is legible in the provider's answer to it.
     */
    private val configurationProblemLabel = JBLabel().apply {
        foreground = JBColor.namedColor("Label.errorForeground", JBColor.RED)
    }

    /** Where the selected configuration's token comes from, and whether it resolves to anything. */
    private val tokenStatusLabel = JBLabel()

    /**
     * The file as it was last read, held so [isModified] and [apply] work against the same list the
     * dropdown was filled from rather than re-reading the file between them.
     */
    private var loaded: AgentConfigurations.Loaded = AgentConfigurations.Loaded(emptyList(), null)

    private val maxIterationsField = JBTextField()

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

    init {
        configurationCombo.addItemListener { event ->
            if (event.stateChange == ItemEvent.SELECTED) updateConfigurationSummary()
        }
    }

    override fun getDisplayName(): String = "AICodingAgent"

    override fun createComponent(): JComponent = panel {
        group("Provider") {
            row("Configuration:") {
                cell(configurationCombo).align(AlignX.FILL)
            }
            row("") {
                cell(configurationSummaryLabel).align(AlignX.FILL)
            }
            row("") {
                cell(configurationProblemLabel).align(AlignX.FILL)
            }.rowComment(
                "Which entry of <code>${AgentConfiguration.FILE_NAME}</code> requests go out with. " +
                    "The file sits in the project root and holds one entry per provider &mdash; " +
                    "name, model, URL, token and token header &mdash; so switching model or " +
                    "provider is this dropdown rather than four fields that are briefly wrong " +
                    "between edits. It is written with three example entries the first time a " +
                    "project is opened, and is never rewritten afterwards.<br/>" +
                    "A <code>token</code> starting with <code>\$</code> names an environment " +
                    "variable (<code>\$${AICodingAgentCredentials.ENV_VAR}</code>) and anything " +
                    "else is the token itself &mdash; the file is plain text and is usually in " +
                    "version control, so the variable is the one to use for anything real. " +
                    "<code>anthropic-version</code> and extra headers live under that entry's " +
                    "<code>additional-customizations</code>.<br/>" +
                    "The URL is the entry's own: <code>${EndpointUrl.ENV_VAR}</code> only says where " +
                    "requests go when there is no usable file, since replacing one entry's URL would " +
                    "leave that entry's protocol and token header pointing at a provider the URL no " +
                    "longer does.",
            )
            row("Token:") {
                cell(tokenStatusLabel).align(AlignX.FILL)
            }
            row {
                button("Reload File") { reloadConfigurations() }
                button("Edit File") { openConfigurationFile() }
                button("Fill In Defaults") { fillInDefaults() }
            }.rowComment(
                "<b>Fill In Defaults</b> writes the file back with every optional field spelled out " +
                    "&mdash; <code>thinking</code>, <code>effort</code>, <code>max-tokens</code>, " +
                    "<code>context-window</code>, the token header and the protocol. Nothing is " +
                    "invented: an entry that leaves a field out is already running on the value it " +
                    "would be given, and this is what puts that value where it can be seen and " +
                    "changed. It reformats the file, so it asks first.",
            )
        }
        group("Conversation") {
            row("Tool calls per message:") {
                cell(maxIterationsField).columns(10)
            }.rowComment(
                "How many rounds of tool calls one message gets before the assistant stops and asks " +
                    "whether to keep going. Answering yes buys another such run, so this is not a " +
                    "ceiling &mdash; it is the check that stops a loop running away unnoticed.<br/>" +
                    "The rest of what a request is made of &mdash; thinking, effort, the reply limit " +
                    "and the context window &mdash; belongs to the model rather than to the loop, and " +
                    "is set per entry in <code>${AgentConfiguration.FILE_NAME}</code>.",
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
        // Against the entry the saved name actually resolves to, not the name itself: a name the
        // file no longer has resolves to its first entry, and the page showing that is not an edit.
        return selectedConfiguration()?.name !=
            AgentConfigurations.select(loaded.configurations, settings.activeConfiguration)?.name ||
            maxIterationsField.positiveIntOr(settings.maxIterations) != settings.maxIterations ||
            mcpServersArea.text != settings.mcpServers ||
            confirmMcpCheckBox.isSelected != settings.confirmMcpToolCalls ||
            skillPathsArea.text != settings.skillPaths ||
            pendingTools != ToolCatalog.parse(settings.enabledTools)
    }

    override fun apply() {
        val settings = AICodingAgentSettingsState.getInstance().state
        // The name rather than the entry: the file is the record of what the entry says, and saving
        // a copy of it here would be a second answer that goes stale the moment the file is edited.
        settings.activeConfiguration = selectedConfiguration()?.name.orEmpty()
        settings.maxIterations = maxIterationsField.positiveIntOr(settings.maxIterations)
        // Put the accepted number back, so a field that was left with something unusable in it
        // shows what actually got saved rather than the text that was ignored.
        maxIterationsField.text = settings.maxIterations.toString()
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
        maxIterationsField.text = settings.maxIterations.toString()
        mcpServersArea.text = settings.mcpServers
        confirmMcpCheckBox.isSelected = settings.confirmMcpToolCalls
        skillPathsArea.text = settings.skillPaths
        pendingTools = ToolCatalog.parse(settings.enabledTools)
        updateToolsSummary()
        reloadConfigurations()
    }

    /**
     * Re-reads the file and refills the dropdown, keeping the selection where it points at something
     * that is still there.
     *
     * The file is edited in the editor behind this dialog, so this is both the reset path and the
     * button next to the dropdown: an entry added while the page is open should be one click away
     * rather than a reason to close the dialog and open it again.
     */
    private fun reloadConfigurations() {
        val settings = AICodingAgentSettingsState.getInstance().state
        val wanted = selectedConfiguration()?.name ?: settings.activeConfiguration
        loaded = project()?.let { AgentConfigurations.getInstance(it).load() }
            ?: AgentConfigurations.Loaded(emptyList(), "no project is open, so there is no file to read")

        configurationCombo.model = DefaultComboBoxModel(loaded.configurations.toTypedArray())
        configurationCombo.isEnabled = loaded.configurations.isNotEmpty()
        configurationCombo.selectedItem = AgentConfigurations.select(loaded.configurations, wanted)
        updateConfigurationSummary()
    }

    private fun selectedConfiguration(): AgentConfiguration? =
        configurationCombo.selectedItem as? AgentConfiguration

    /**
     * Says what the selection will actually send, and what stops it if anything does.
     *
     * The same check the agent runs before a turn, so a configuration that cannot work says so here
     * instead of a provider saying it in a 400 that names a parameter rather than a setting.
     */
    private fun updateConfigurationSummary() {
        val selected = selectedConfiguration()
        if (selected == null) {
            configurationSummaryLabel.text = ""
            tokenStatusLabel.text = "No configuration is selected."
            configurationProblemLabel.text = loaded.error?.let { "Falling back to the built-in default: $it" }
                ?: "No configurations were found."
            return
        }

        val headers = selected.extraHeaders.keys.joinToString(", ").ifBlank { "none" }
        // Everything interpolated here came out of a file the user wrote, so it is escaped one value
        // at a time rather than left to a label that would read a stray < as markup.
        configurationSummaryLabel.text = buildString {
            append("<html>")
            append("Model: <b>").append(escape(selected.model)).append("</b>")
            append(" &nbsp;&middot;&nbsp; API: ").append(escape(selected.protocol.displayName.substringBefore(" (")))
            append(" &nbsp;&middot;&nbsp; Token header: <code>").append(escape(selected.authScheme.headerName)).append("</code>")
            append("<br/>URL: <code>").append(escape(selected.url)).append("</code>")
            append("<br/>Thinking: ").append(escape(selected.thinking.fileName))
            append(" &nbsp;&middot;&nbsp; Effort: ").append(escape(selected.effort.fileName))
            append(" &nbsp;&middot;&nbsp; Max tokens: ").append(selected.maxTokens)
            append(" &nbsp;&middot;&nbsp; Context window: ")
            append(if (selected.contextWindowTokens == 0) "unlimited" else selected.contextWindowTokens.toString())
            append("<br/>anthropic-version: <code>").append(escape(selected.apiVersion.ifBlank { "not sent" })).append("</code>")
            append(" &nbsp;&middot;&nbsp; Extra headers: ").append(escape(headers))
            append("</html>")
        }
        tokenStatusLabel.text = selected.tokenDescription

        val problem = AICodingAgentEndpoint.from(selected).validate()
        configurationProblemLabel.text =
            problem?.let { "This configuration cannot be used: $it" } ?: loaded.error.orEmpty()
    }

    private fun escape(value: String): String = StringUtil.escapeXmlEntities(value)

    /**
     * Opens the file in the editor, creating it first if this project has never had one.
     *
     * Behind the settings dialog rather than in it: it is JSON that is edited by hand, and the
     * editor is better at that than any field this page could offer.
     */
    private fun openConfigurationFile() {
        val project = project() ?: run {
            Messages.showInfoMessage("Open a project first -- the file lives in the project root.", "Configuration File")
            return
        }
        val file = AgentConfigurations.getInstance(project).virtualFile() ?: run {
            Messages.showErrorDialog(
                "Could not create ${AgentConfiguration.FILE_NAME} in ${project.basePath}.",
                "Configuration File",
            )
            return
        }
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    /**
     * Rewrites the file with the defaults every entry is already running on written out.
     *
     * Runs off what was last read rather than re-reading, so what gets written is what the dropdown
     * and the summary above were showing -- a file edited behind this dialog since then is caught by
     * the no-op check, which compares the text it would write against the text that is there.
     */
    private fun fillInDefaults() {
        val project = project() ?: run {
            Messages.showInfoMessage("Open a project first -- the file lives in the project root.", "Configuration File")
            return
        }
        val service = AgentConfigurations.getInstance(project)
        val current = service.load()
        if (current.configurations.isEmpty()) {
            Messages.showErrorDialog(
                current.error ?: "There is nothing in ${AgentConfiguration.FILE_NAME} to write back.",
                "Configuration File",
            )
            return
        }
        if (service.text() == AgentConfiguration.render(current.configurations)) {
            Messages.showInfoMessage(
                "${AgentConfiguration.FILE_NAME} already spells out every field.",
                "Configuration File",
            )
            return
        }

        val confirmed = Messages.showYesNoDialog(
            project,
            "This rewrites ${AgentConfiguration.FILE_NAME} with every optional field written out. " +
                "The values you have set are kept -- only the ones you left out are added, at the " +
                "defaults they are already running on. Your formatting and the order of the fields " +
                "are not.",
            "Fill In Defaults",
            "Rewrite File",
            "Cancel",
            Messages.getQuestionIcon(),
        )
        if (confirmed != Messages.YES) return

        val failure = service.rewrite(current.configurations)
        if (failure != null) {
            Messages.showErrorDialog(failure, "Configuration File")
            return
        }
        reloadConfigurations()
    }

    private fun project(): Project? = ProjectManager.getInstance().openProjects.firstOrNull()

    /**
     * Opens the picker on what is pending rather than on what is saved, so cancelling this page
     * after choosing tools discards them along with every other unapplied edit.
     */
    private fun chooseTools() {
        val dialog = ToolSelectionDialog(project(), pendingTools)
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
     * The number typed into the field, or [fallback] when it is not a usable one. It is a budget the
     * code counts down, so anything but a positive integer -- empty, a word, a zero -- is treated as
     * "leave it alone" rather than saved and acted on.
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
        val project: Project? = project()
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

        val project: Project? = project()
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
