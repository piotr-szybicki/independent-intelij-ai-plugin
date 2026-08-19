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
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.github.piotrszybicki.independentintelijaiplugin.agents.AgentCatalog
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentEndpoint
import com.github.piotrszybicki.independentintelijaiplugin.logging.ModelUsageDatabase
import com.github.piotrszybicki.independentintelijaiplugin.mcp.McpConfigException
import com.github.piotrszybicki.independentintelijaiplugin.mcp.McpServerConfig
import com.github.piotrszybicki.independentintelijaiplugin.mcp.McpService
import com.github.piotrszybicki.independentintelijaiplugin.mcp.McpTool
import com.github.piotrszybicki.independentintelijaiplugin.skills.SkillCatalog
import com.github.piotrszybicki.independentintelijaiplugin.skills.SkillRoot
import com.github.piotrszybicki.independentintelijaiplugin.tools.ToolCatalog
import com.github.piotrszybicki.independentintelijaiplugin.tools.ToolCategory
import com.github.piotrszybicki.independentintelijaiplugin.toolWindow.ChatToolWindowFactory
import java.awt.event.ItemEvent
import java.io.File
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent

class AICodingAgentSettingsConfigurable : Configurable {

    private val configurationCombo = ComboBox(DefaultComboBoxModel<String>())

    private val modelCombo = ComboBox(DefaultComboBoxModel<String>())

    private val configurationSummaryLabel = JBLabel().apply {
        setComponentStyle(UIUtil.ComponentStyle.SMALL)
        setFontColor(UIUtil.FontColor.BRIGHTER)
    }

    private val configurationProblemLabel = JBLabel().apply {
        foreground = JBColor.namedColor("Label.errorForeground", JBColor.RED)
    }

    private val tokenStatusLabel = JBLabel()

    private var loaded: AgentConfigurations.Loaded = AgentConfigurations.Loaded(emptyList(), null)

    private val maxIterationsField = JBTextField()

    private val maxToolOutputField = JBTextField()

    private val mcpServersArea = JBTextArea(10, 40).apply {
        lineWrap = false
        font = JBUI.Fonts.create("Monospaced", font.size)
    }

    private val confirmMcpCheckBox = JBCheckBox("Ask before each MCP tool call")

    private var pendingTools: Set<String> = emptySet()

    private var pendingMcpTools: Set<String> = emptySet()

    private val toolsSummaryLabel = JBLabel()

    private val mcpToolsSummaryLabel = JBLabel()

    private var pendingSkills: Set<String> = emptySet()

    private val skillsSummaryLabel = JBLabel()

    private val skillPathsArea = JBTextArea(5, 40).apply {
        lineWrap = false
        font = JBUI.Fonts.create("Monospaced", font.size)
    }

    private var loadedDatabase: AgentConfigurations.LoadedDatabase =
        AgentConfigurations.LoadedDatabase(UsageDatabaseConfig.OFF, null)

    private var lastSeenDatabase: UsageDatabaseConfig? = null

    private val usageDatabaseLabel = JBLabel().apply {
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
            row("Default configuration:") {
                cell(configurationCombo).align(AlignX.FILL)
            }
            row("Default model:") {
                cell(modelCombo).align(AlignX.FILL)
            }.rowComment(
                "What a <b>new</b> chat starts on. Each conversation then keeps its own provider and " +
                    "model &mdash; the two dropdowns above the transcript change that chat alone, " +
                    "and a chat reopened from the history comes back on what it was sent to.<br/>" +
                    "Changing either dropdown in a chat also updates the default here, so the next " +
                    "new chat carries on where the last one left off rather than snapping back.",
            )
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
                    "Set <code>${AgentConfiguration.PATH_ENV_VAR}</code> to a path and that file is " +
                    "read instead &mdash; one set of providers for every project on the machine, " +
                    "kept outside version control. A directory means " +
                    "<code>${AgentConfiguration.FILE_NAME}</code> inside it. Such a file is only " +
                    "ever read: nothing is written to it and no starter file is created, and if it " +
                    "is not there the tool window refuses to open rather than falling back to a " +
                    "provider you did not choose.<br/>" +
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
            row("Tokens per tool result:") {
                cell(maxToolOutputField).columns(10)
            }.rowComment(
                "The most one tool call may return before the assistant refuses to send it. The " +
                    "output is counted with a real tokenizer (JTokkit, the JVM port of OpenAI's " +
                    "tiktoken); over the limit it is withheld, the turn stops, and the transcript " +
                    "says which call it was and how big.<br/>" +
                    "The output itself is still shown in the chat &mdash; it is the model's copy " +
                    "that is dropped, not yours &mdash; and the conversation stays usable: your " +
                    "next message carries on, and the model is told the result was withheld so it " +
                    "asks for less rather than repeating the call.<br/>" +
                    "It is deliberately low. What a tool returns is re-sent with every later " +
                    "request in the conversation, so one search that matched half the project is " +
                    "paid for again and again. <b>0</b> turns the check off.",
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
                cell(mcpToolsSummaryLabel).align(AlignX.FILL)
            }
            row {
                button("Test Servers") { testServers() }
                button("Select MCP Tools…") { chooseMcpTools() }
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
                cell(skillsSummaryLabel).align(AlignX.FILL)
            }
            row {
                button("Scan for Skills") { scanSkills() }
                button("Select Skills…") { chooseSkills() }
            }
        }
        group("Logging") {
            row("Usage database:") {
                cell(usageDatabaseLabel).align(AlignX.FILL)
            }.rowComment(
                "A MySQL JDBC URL, in the <code>${UsageDatabaseConfig.SECTION}</code> section of " +
                    "<code>${AgentConfiguration.FILE_NAME}</code> beside the providers &mdash; it " +
                    "names a server that belongs to the project, so it travels with it. " +
                    "<b>Edit File</b> and <b>Reload File</b> above are how it is changed and re-read.<br/>" +
                    "<code>\"${UsageDatabaseConfig.SECTION}\": {\"url\": " +
                    "\"jdbc:mysql://localhost:3306/ai_usage?user=root&amp;password=\${env:MYSQL_PASSWORD}\", " +
                    "\"enabled\": true}</code><br/>" +
                    "Leave the URL empty and nothing is recorded &mdash; there is no sensible guess " +
                    "to make about where a server is. <code>enabled</code> is what stops the writing " +
                    "without losing the URL. Write <code>\${env:NAME}</code> for anything secret: " +
                    "the file is plain text and usually in version control. The database and the " +
                    "<code>${ModelUsageDatabase.TABLE}</code> table are created if they are not " +
                    "there, so the URL may name a database that does not exist yet.",
            )
            row {
                button("Test Connection") { testUsageDatabase() }
            }.rowComment(
                "One row per request &mdash; when it was sent, which conversation, to which " +
                    "provider, model and URL, the status code, how long it took, the error if it " +
                    "never came back, and the input, cached and output token counts. What a chat " +
                    "cost, or which model is missing its cache, is then one query.<br/>" +
                    "<code>cost_usd</code> holds the same estimate the chat shows under each reply, " +
                    "so <code>SUM(cost_usd)</code> compares chats across models rather than comparing " +
                    "tokens that are not worth the same. The rates are list prices kept in the " +
                    "plugin, so it is an estimate and not a bill, and it is empty for a model with " +
                    "no price listed &mdash; a local one, or one newer than the plugin.<br/>" +
                    "The request and response bodies go in too, as <code>JSON</code> columns, so " +
                    "<code>request_body-&gt;&gt;'\$.model'</code> is a column expression rather than " +
                    "a file to open. <b>The table grows roughly by the size of the conversation per " +
                    "request</b> because of them. The same bodies are still written as files under " +
                    "<code>exchanges/</code>, which <code>conversation_id</code> and " +
                    "<code>request_id</code> name the directory of.<br/>" +
                    "Writes happen on a background thread, so a server that is down slows nothing " +
                    "up: the rows are dropped and a line goes to <code>idea.log</code>. " +
                    "<b>Test Connection</b> runs against the URL in the file as it stands, creating " +
                    "the database and the table if they are not there, and reports the server " +
                    "version, the row count and what has been recorded so far.",
            )
        }
    }.also { reset() }

    override fun isModified(): Boolean {
        val settings = AICodingAgentSettingsState.getInstance().state
        return selectedConfiguration()?.name !=
            AgentConfigurations.select(loaded.configurations, settings.activeConfiguration)?.name ||
            modelCombo.selectedItem != selectedConfiguration()?.withModel(settings.activeModel)?.model ||
            maxIterationsField.positiveIntOr(settings.maxIterations) != settings.maxIterations ||
            maxToolOutputField.zeroOrPositiveIntOr(settings.maxToolOutputTokens) != settings.maxToolOutputTokens ||
            mcpServersArea.text != settings.mcpServers ||
            confirmMcpCheckBox.isSelected != settings.confirmMcpToolCalls ||
            skillPathsArea.text != settings.skillPaths ||
            pendingTools != ToolCatalog.parse(settings.enabledTools) ||
            pendingMcpTools != parseMcpTools(settings.enabledMcpTools) ||
            pendingSkills != parseMcpTools(settings.enabledSkills)
    }

    override fun apply() {
        val settings = AICodingAgentSettingsState.getInstance().state
        settings.activeConfiguration = selectedConfiguration()?.name.orEmpty()
        settings.activeModel = modelCombo.selectedItem as? String ?: ""
        settings.maxIterations = maxIterationsField.positiveIntOr(settings.maxIterations)
        settings.maxToolOutputTokens = maxToolOutputField.zeroOrPositiveIntOr(settings.maxToolOutputTokens)
        maxIterationsField.text = settings.maxIterations.toString()
        maxToolOutputField.text = settings.maxToolOutputTokens.toString()
        settings.skillPaths = skillPathsArea.text
        settings.enabledTools = ToolCatalog.format(pendingTools)
        settings.enabledMcpTools = if (pendingMcpTools.isEmpty()) "" else pendingMcpTools.joinToString(",")
        settings.enabledSkills = if (pendingSkills.isEmpty()) "" else pendingSkills.joinToString(",")

        val serversChanged = settings.mcpServers != mcpServersArea.text
        settings.mcpServers = mcpServersArea.text
        settings.confirmMcpToolCalls = confirmMcpCheckBox.isSelected
        if (serversChanged) {
            ProjectManager.getInstance().openProjects.forEach { McpService.getInstance(it).reload() }
        }
        ChatToolWindowFactory.refreshProviderBars()
    }

    override fun reset() {
        val settings = AICodingAgentSettingsState.getInstance().state
        maxIterationsField.text = settings.maxIterations.toString()
        maxToolOutputField.text = settings.maxToolOutputTokens.toString()
        mcpServersArea.text = settings.mcpServers
        confirmMcpCheckBox.isSelected = settings.confirmMcpToolCalls
        skillPathsArea.text = settings.skillPaths
        pendingTools = ToolCatalog.parse(settings.enabledTools)
        pendingMcpTools = parseMcpTools(settings.enabledMcpTools)
        pendingSkills = parseMcpTools(settings.enabledSkills)
        updateToolsSummary()
        updateMcpToolsSummary()
        updateSkillsSummary()
        reloadConfigurations()
    }

    private fun reloadConfigurations() {
        val settings = AICodingAgentSettingsState.getInstance().state
        val wanted = configurationCombo.selectedItem as? String ?: settings.activeConfiguration
        loaded = project()?.let { AgentConfigurations.getInstance(it).load() }
            ?: AgentConfigurations.Loaded(emptyList(), "no project is open, so there is no file to read")

        configurationCombo.model = DefaultComboBoxModel(loaded.configurations.map { it.name }.toTypedArray())
        configurationCombo.isEnabled = loaded.configurations.isNotEmpty()
        configurationCombo.selectedItem = AgentConfigurations.select(loaded.configurations, wanted)?.name
        updateConfigurationSummary()
        reloadUsageDatabase()
    }

    private fun reloadUsageDatabase() {
        val previous = lastSeenDatabase
        loadedDatabase = project()?.let { AgentConfigurations.getInstance(it).usageDatabase() }
            ?: AgentConfigurations.LoadedDatabase(UsageDatabaseConfig.OFF, null)
        val database = loadedDatabase.database
        lastSeenDatabase = database
        if (previous != null && previous != database) ModelUsageDatabase.close()

        val problem = loadedDatabase.error
        usageDatabaseLabel.text = when {
            problem != null -> problem
            database.url.isBlank() -> "Not configured -- nothing is recorded."
            !database.enabled -> "Switched off (\"enabled\": false) -- ${database.url}"
            else -> "<html><code>${escape(database.url)}</code></html>"
        }
        usageDatabaseLabel.foreground = if (problem != null) {
            JBColor.namedColor("Label.errorForeground", JBColor.RED)
        } else {
            UIUtil.getLabelForeground()
        }
    }

    private fun selectedConfiguration(): AgentConfiguration? =
        loaded.configurations.firstOrNull { it.name == configurationCombo.selectedItem }

    private fun updateConfigurationSummary() {
        val selected = selectedConfiguration()
        modelCombo.model = DefaultComboBoxModel((selected?.models ?: emptyList()).toTypedArray())
        modelCombo.isEnabled = selected?.models?.isNotEmpty() == true
        modelCombo.selectedItem = selected
            ?.withModel(AICodingAgentSettingsState.getInstance().state.activeModel)
            ?.model

        if (selected == null) {
            configurationSummaryLabel.text = ""
            tokenStatusLabel.text = "No configuration is selected."
            configurationProblemLabel.text = loaded.error?.let { "Falling back to the built-in default: $it" }
                ?: "No configurations were found."
            return
        }

        val headers = selected.extraHeaders.keys.joinToString(", ").ifBlank { "none" }
        configurationSummaryLabel.text = buildString {
            append("<html>")
            append("Models offered: ").append(escape(selected.models.joinToString(", ")))
            append("<br/>API: ").append(escape(selected.protocol.displayName.substringBefore(" (")))
            append(" &nbsp;&middot;&nbsp; Token header: <code>").append(escape(selected.authScheme.headerName)).append("</code>")
            append("<br/>URL: <code>").append(escape(selected.url)).append("</code>")
            append("<br/>Thinking: ").append(escape(selected.thinking.fileName))
            append(" &nbsp;&middot;&nbsp; Effort: ").append(escape(selected.effort.fileName))
            append(" &nbsp;&middot;&nbsp; Max tokens: ").append(selected.maxTokens)
            append(" &nbsp;&middot;&nbsp; Context window: ")
            append(if (selected.contextWindowTokens == 0) "unlimited" else selected.contextWindowTokens.toString())
            append(" &nbsp;&middot;&nbsp; Request timeout: ").append(selected.requestTimeoutSeconds).append("s")
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

    private fun openConfigurationFile() {
        val project = project() ?: run {
            Messages.showInfoMessage("Open a project first -- the file lives in the project root.", "Configuration File")
            return
        }
        val service = AgentConfigurations.getInstance(project)
        val file = service.virtualFile() ?: run {
            val message = if (service.isExternal) {
                "\$${AgentConfiguration.PATH_ENV_VAR} names ${service.path}, which is not there.\n\n" +
                    "Nothing is created while that variable is set. Create the file at that path, or " +
                    "unset the variable to use ${AgentConfiguration.FILE_NAME} in the project root."
            } else {
                "Could not create ${AgentConfiguration.FILE_NAME} in ${project.basePath}."
            }
            Messages.showErrorDialog(message, "Configuration File")
            return
        }
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    private fun fillInDefaults() {
        val project = project() ?: run {
            Messages.showInfoMessage("Open a project first -- the file lives in the project root.", "Configuration File")
            return
        }
        val service = AgentConfigurations.getInstance(project)
        if (service.isExternal) {
            Messages.showInfoMessage(
                "${service.path} is only read, never written, because " +
                    "\$${AgentConfiguration.PATH_ENV_VAR} names it.\n\n" +
                    "Fill in the fields yourself, or unset that variable to have the plugin manage " +
                    "${AgentConfiguration.FILE_NAME} in the project root.",
                "Configuration File",
            )
            return
        }
        val current = service.load()
        if (current.configurations.isEmpty()) {
            Messages.showErrorDialog(
                current.error ?: "There is nothing in ${AgentConfiguration.FILE_NAME} to write back.",
                "Configuration File",
            )
            return
        }
        val database = service.usageDatabase()
        if (database.error != null) {
            Messages.showErrorDialog(
                "${database.error}\n\nFix that section first -- rewriting the file now would drop it.",
                "Configuration File",
            )
            return
        }
        val findInFiles = service.findInFiles()
        if (findInFiles.error != null) {
            Messages.showErrorDialog(
                "${findInFiles.error}\n\nFix that section first -- rewriting the file now would drop it.",
                "Configuration File",
            )
            return
        }
        val agents = service.agents()
        if (agents.error != null) {
            Messages.showErrorDialog(
                "${agents.error}\n\nFix that section first -- rewriting the file now would drop it.",
                "Configuration File",
            )
            return
        }
        val roster = AgentCatalog.rosterFor(project)

        if (service.text() ==
            AgentConfiguration.render(current.configurations, database.database, findInFiles.findInFiles, roster)
        ) {
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

        val failure = service.rewrite(current.configurations, database.database, findInFiles.findInFiles, roster)
        if (failure != null) {
            Messages.showErrorDialog(failure, "Configuration File")
            return
        }
        reloadConfigurations()
    }

    private fun project(): Project? = ProjectManager.getInstance().openProjects.firstOrNull()

    private fun chooseTools() {
        val dialog = ToolSelectionDialog(project(), pendingTools)
        if (dialog.showAndGet()) {
            pendingTools = dialog.selectedTools
            updateToolsSummary()
        }
    }

    private fun updateToolsSummary() {
        val byCategory = ToolCategory.entries.joinToString(", ") { category ->
            val inCategory = ToolCatalog.entries.filter { it.category == category }
            "${category.displayName} ${inCategory.count { it.name in pendingTools }}/${inCategory.size}"
        }
        toolsSummaryLabel.text =
            "${pendingTools.size} of ${ToolCatalog.entries.size} tools selected -- $byCategory"
    }

    private fun JBTextField.positiveIntOr(fallback: Int): Int =
        text.trim().toIntOrNull()?.takeIf { it > 0 } ?: fallback

    private fun JBTextField.zeroOrPositiveIntOr(fallback: Int): Int =
        text.trim().toIntOrNull()?.takeIf { it >= 0 } ?: fallback

    private fun chooseSkills() {
        val project: Project? = project()
        val roots = SkillRoot.parseAll(skillPathsArea.text, project?.basePath?.let(::File))
        if (roots.isEmpty()) {
            Messages.showInfoMessage("No skill directories are configured.", "Skills")
            return
        }

        val scan = SkillCatalog.scan(roots)
        if (scan.skills.isEmpty()) {
            Messages.showInfoMessage("No skills were found in the configured directories.", "Skills")
            return
        }

        val dialog = SkillSelectionDialog(project, scan.skills, pendingSkills)
        if (dialog.showAndGet()) {
            pendingSkills = dialog.selectedSkills
            updateSkillsSummary()
        }
    }

    private fun updateSkillsSummary() {
        skillsSummaryLabel.text = if (pendingSkills.isEmpty()) {
            "All skills enabled (no selection made)"
        } else {
            "${pendingSkills.size} skill(s) selected"
        }
    }

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

    private fun testUsageDatabase() {
        reloadUsageDatabase()
        loadedDatabase.error?.let {
            Messages.showErrorDialog(it, "Usage Database")
            return
        }
        val url = loadedDatabase.database.url
        if (url.isBlank()) {
            Messages.showInfoMessage(
                "No database URL is configured. Add a \"${UsageDatabaseConfig.SECTION}\" section to " +
                    "${AgentConfiguration.FILE_NAME}.",
                "Usage Database",
            )
            return
        }

        val task = object : Task.Modal(project(), "Connecting to the Usage Database", true) {
            var report = ""

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                report = ModelUsageDatabase.test(url)
            }

            override fun onSuccess() = Messages.showInfoMessage(report, "Usage Database")
        }
        ProgressManager.getInstance().run(task)
    }

    private fun chooseMcpTools() {
        val configs = try {
            McpServerConfig.parseAll(mcpServersArea.text)
        } catch (e: McpConfigException) {
            Messages.showErrorDialog("The MCP configuration is ${e.message}", "MCP Tools")
            return
        }
        if (configs.isEmpty() || configs.none { it.enabled }) {
            Messages.showInfoMessage("No enabled MCP servers are configured.", "MCP Tools")
            return
        }

        val project: Project? = project()
        var entries = emptyList<McpToolSelectionDialog.McpToolEntry>()

        val task = object : Task.Modal(project, "Discovering MCP Tools", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val results = McpService.probeTools(project, configs)
                val usedNames = mutableSetOf<String>()
                entries = results.flatMap { result ->
                    if (result.error != null) return@flatMap emptyList()
                    result.tools.map { descriptor ->
                        val base = McpTool.qualifiedName(result.serverName, descriptor.name)
                        var name = base
                        var suffix = 2
                        while (!usedNames.add(name)) name = base.take(124) + "_" + suffix++
                        McpToolSelectionDialog.McpToolEntry(result.serverName, descriptor.name, name, descriptor.description)
                    }
                }
            }

            override fun onSuccess() {
                if (entries.isEmpty()) {
                    Messages.showInfoMessage("No tools were found on any MCP server.", "MCP Tools")
                    return
                }
                val dialog = McpToolSelectionDialog(project, entries, pendingMcpTools)
                if (dialog.showAndGet()) {
                    pendingMcpTools = dialog.selectedTools
                    updateMcpToolsSummary()
                }
            }
        }
        ProgressManager.getInstance().run(task)
    }

    private fun updateMcpToolsSummary() {
        mcpToolsSummaryLabel.text = if (pendingMcpTools.isEmpty()) {
            "All MCP tools enabled (no selection made)"
        } else {
            "${pendingMcpTools.size} MCP tool(s) selected"
        }
    }

    private fun parseMcpTools(text: String): Set<String> =
        if (text.isBlank()) emptySet() else text.split(",").mapTo(mutableSetOf()) { it.trim() }

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
