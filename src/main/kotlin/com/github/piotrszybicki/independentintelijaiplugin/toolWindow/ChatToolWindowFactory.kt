package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.google.gson.JsonObject
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.InplaceButton
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.github.piotrszybicki.independentintelijaiplugin.agents.AgentCatalog
import com.github.piotrszybicki.independentintelijaiplugin.agents.AgentDefinition
import com.github.piotrszybicki.independentintelijaiplugin.agents.AgentFiles
import com.github.piotrszybicki.independentintelijaiplugin.agents.AgentHandoff
import com.github.piotrszybicki.independentintelijaiplugin.agents.AgentReturn
import com.github.piotrszybicki.independentintelijaiplugin.agents.AgentSession
import com.github.piotrszybicki.independentintelijaiplugin.agents.AgentToolPolicy
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgent
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentEndpoint
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentUsage
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.ChatMessage
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.ContextMeter
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.HistoryCompaction
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.ReasoningOptions
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.SessionUsage
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.TokenCounter
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.ToolResults
import com.github.piotrszybicki.independentintelijaiplugin.changes.ChangeSessionService
import com.github.piotrszybicki.independentintelijaiplugin.changes.ChangeTrackingTool
import com.github.piotrszybicki.independentintelijaiplugin.history.ChatHistoryService
import com.github.piotrszybicki.independentintelijaiplugin.history.StoredChat
import com.github.piotrszybicki.independentintelijaiplugin.history.StoredRow
import com.github.piotrszybicki.independentintelijaiplugin.mcp.McpService
import com.github.piotrszybicki.independentintelijaiplugin.settings.AICodingAgentSettingsConfigurable
import com.github.piotrszybicki.independentintelijaiplugin.settings.AICodingAgentSettingsState
import com.github.piotrszybicki.independentintelijaiplugin.settings.AgentConfiguration
import com.github.piotrszybicki.independentintelijaiplugin.settings.AgentConfigurations
import com.github.piotrszybicki.independentintelijaiplugin.settings.ConversationTools
import com.github.piotrszybicki.independentintelijaiplugin.skills.SkillCatalog
import com.github.piotrszybicki.independentintelijaiplugin.skills.SkillDefinition
import com.github.piotrszybicki.independentintelijaiplugin.tools.ProjectEnvironment
import com.github.piotrszybicki.independentintelijaiplugin.tools.RunShellCommandTool
import com.github.piotrszybicki.independentintelijaiplugin.tools.SummarizeTool
import com.github.piotrszybicki.independentintelijaiplugin.tools.ToolCatalog
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.nio.file.Path
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import kotlin.math.roundToInt

class ChatToolWindowFactory : ToolWindowFactory {

    companion object {
        private const val TOOL_WINDOW_ID = "AICodingAgent"

        private val panels = java.util.WeakHashMap<Project, ChatPanel>()

        fun openSideChat(project: Project, code: String, displayPath: String, extension: String, lineRange: String) {
            val toolWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                .getToolWindow(TOOL_WINDOW_ID) ?: return
            toolWindow.show {
                val panel = panels[project] ?: return@show
                panel.startSideChat(code, displayPath, extension, lineRange)
            }
        }

        fun refreshProviderBars() = panels.values.forEach { it.refreshProviderBar() }

        internal fun projectOpened(project: Project) {
            ApplicationManager.getApplication().invokeLater({
                openedProjects += project
                panels[project]?.restoreLastChatNow()
            }, project.disposed)
        }

        private val openedProjects: MutableSet<Project> =
            java.util.Collections.newSetFromMap(java.util.WeakHashMap())

        internal fun isOpened(project: Project) = project in openedProjects
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        AgentConfigurations.getInstance(project).unavailableReason?.let { reason ->
            val content = ContentFactory.getInstance().createContent(unavailablePanel(reason), null, false)
            toolWindow.contentManager.addContent(content)
            return
        }

        val chatPanel = ChatPanel(project)
        panels[project] = chatPanel
        val content = ContentFactory.getInstance().createContent(chatPanel.component, null, false)
        Disposer.register(content, chatPanel)
        toolWindow.contentManager.addContent(content)
        toolWindow.setTitleActions(
            listOf(
                DumbAwareAction.create("New Chat", AllIcons.General.Add) { chatPanel.startNewChat() },
                DumbAwareAction.create("Chat History", AllIcons.Vcs.History) { e -> chatPanel.showHistory(e) },
                DumbAwareAction.create("Settings", AllIcons.General.Settings) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, AICodingAgentSettingsConfigurable::class.java)
                },
            )
        )
    }

    override fun shouldBeAvailable(project: Project) = true

    private fun unavailablePanel(reason: String): JComponent = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(16)
        add(
            JBLabel(
                "<html><b>AICodingAgent is not available.</b><br/><br/>" +
                    StringUtil.escapeXmlEntities(reason) + "<br/><br/>" +
                    "Create the file at that path, or unset <code>${AgentConfiguration.PATH_ENV_VAR}</code> " +
                    "and restart the IDE to use <code>${AgentConfiguration.FILE_NAME}</code> in the " +
                    "project root.</html>"
            ).apply { verticalAlignment = SwingConstants.TOP },
            BorderLayout.CENTER,
        )
    }

    private class ChatPanel(private val project: Project) : Disposable {

        private val history = mutableListOf<ChatMessage>()
        private val rows = mutableListOf<StoredRow>()
        private var runningTool: ChatTranscript.RunningTool? = null
        private var runningToolIndex = -1
        private val withheldCards = mutableMapOf<String, ChatTranscript.RunningTool>()

        private val chatHistory by lazy { ChatHistoryService.getInstance(project) }
        private var chatId = ChatHistoryService.newChatId()
        private var chatCreatedAt = System.currentTimeMillis()
        private val restoreStarted = AtomicBoolean(false)
        private var raisedMaxTokens = 0
        private var usage = SessionUsage()
        private val contextMeter = ContextMeter()

        private val session = ChangeSessionService.getInstance(project)
        private val mcp by lazy { McpService.getInstance(project) }
        private val builtInTools = ToolCatalog.buildAll(project)
        private val shellTool = builtInTools.filterIsInstance<RunShellCommandTool>().firstOrNull()
        private val summarizeTool = builtInTools.filterIsInstance<SummarizeTool>().firstOrNull()
        private val log = Logger.getInstance(ChatToolWindowFactory::class.java)

        private var activeAgent: AgentDefinition? = null
        private var agentSession: AgentSession? = null

        /* What this one chat may call, when it has been given a set of its own. */
        private var conversationTools = ConversationTools.INHERIT

        /* Skills the user invoked with / and that the next message has still to carry. */
        private val invokedSkills = mutableListOf<SkillDefinition>()

        private fun agentTools(): List<AICodingAgentTool> {
            val selected = if (conversationTools.overridden) {
                val wanted = conversationTools.toolNames + conversationTools.mcpToolNames
                (builtInTools + mcp.allTools()).filter { it.name in wanted }
            } else {
                val defaults = defaultTools()
                (activeAgent?.tools ?: AgentToolPolicy.INHERIT)
                    .select(everything = defaults.everything, enabledInSettings = defaults.enabled)
            }
            return ChangeTrackingTool.wrapAll(selected, session)
        }

        private class DefaultTools(
            val everything: List<AICodingAgentTool>,
            val enabled: List<AICodingAgentTool>,
        )

        /*
         * The layer under the agent and under this chat: the `conversation-defaults` section of
         * the settings file where it names tools, and the settings page where it does not. A named
         * but empty array there means none of that kind, which is why the section is asked whether
         * it said anything rather than whether it listed anything.
         */
        private fun defaultTools(): DefaultTools {
            val defaults = AgentConfigurations.getInstance(project).conversationDefaults().defaults
            val mcpTools = if (defaults.mcpTools == null) mcp.tools() else mcp.allTools()
            val enabledBuiltIn = defaults.tools?.toSet()
                ?.let { names -> builtInTools.filter { it.name in names } }
                ?: ToolCatalog.enabledIn(builtInTools)
            val enabledMcp = defaults.mcpTools?.toSet()
                ?.let { names -> mcpTools.filter { it.name in names } }
                ?: mcpTools
            return DefaultTools(builtInTools + mcpTools, enabledBuiltIn + enabledMcp)
        }

        /* The skills every new chat in this project starts with, which is none unless the file says. */
        private fun defaultConversationTools(): ConversationTools =
            ConversationTools(
                skills = AgentConfigurations.getInstance(project).conversationDefaults().defaults.skills,
            )

        /*
         * The tools this chat runs with when it has no selection of its own: the defaults above,
         * narrowed by the agent's tool list. The dialog shows it while the chat is inheriting, and
         * starts from it when the chat is given its own set. Skills are not in it -- they are never
         * inherited from one chat to the next, only started with.
         */
        private fun inheritedTools(): ConversationTools {
            val defaults = defaultTools()
            val selected = (activeAgent?.tools ?: AgentToolPolicy.INHERIT)
                .select(everything = defaults.everything, enabledInSettings = defaults.enabled)
                .map { it.name }
            val builtInNames = builtInTools.mapTo(mutableSetOf()) { it.name }
            return ConversationTools(
                tools = selected.filter { it in builtInNames },
                mcpTools = selected.filter { it !in builtInNames },
            )
        }

        private val toolsButton = ConversationToolsButton(
            project = project,
            mcpTools = { mcp.allTools() },
            inherited = { inheritedTools() },
            onChanged = { chosen -> conversationToolsChosen(chosen) },
        )

        private fun conversationToolsChosen(chosen: ConversationTools) {
            applyConversationTools(chosen)
            statusLabel.text = chosen.describe() + " It takes effect from the next message."
            saveCurrentChat(active = true)
        }

        /*
         * The / popup's half of the same choice. Unlike the checkbox it is also an instruction:
         * the skill is switched on for the rest of the chat and the next message carries a block
         * telling the model to read it and follow it now, which is what typing /name means.
         */
        private fun addSkill(skill: SkillDefinition) {
            applyConversationTools(conversationTools.withSkill(skill.name))
            if (invokedSkills.none { it.name == skill.name }) invokedSkills += skill
            statusLabel.text = "/${skill.name} will be used for the next message, and stays available after it."
            saveCurrentChat(active = true)
        }

        private fun applyConversationTools(chosen: ConversationTools) {
            conversationTools = chosen
            toolsButton.set(chosen)
            agentBanner.show(agentSession, toolsDescription())
        }

        /* What is actually in force: this chat's own set when it has one, the agent's list otherwise. */
        private fun toolsDescription(): String? =
            if (conversationTools.isCustom) conversationTools.describe() else activeAgent?.tools?.describe()

        private val agent = AICodingAgent(
            tools = { agentTools() },
            environment = { ProjectEnvironment.describe(project) },
            skills = { SkillCatalog.describe(project, conversationTools.activeSkills) },
        )

        private val transcript = ChatTranscript(
            project,
            onCancel = { cancelTurn() },
            onContinue = { continueTurn() },
        )

        private val cancelled = AtomicBoolean(false)
        private var turn: Future<*>? = null

        private val input = ChatInputPane("Ask AI about this project…")

        private val sendButton = JButton("Send").apply {
            toolTipText = "Send message (Enter; Shift+Enter for a new line)"
        }

        private val attachButton = InplaceButton(
            "Attach the editor selection, or the whole file when nothing is selected " +
                "(press again with another file open to attach several)",
            AllIcons.Actions.AddFile,
        ) { chatAttachments.attachFromEditor() }

        private val statusLabel = JBLabel(" ").apply {
            font = JBFont.small()
            foreground = ChatColors.muted
        }

        private val usageLabel = JBLabel().apply {
            font = JBFont.small()
            foreground = ChatColors.muted
        }

        private val contextLabel = JBLabel().apply {
            font = JBFont.small()
            foreground = ChatColors.muted
        }


        private val providerBar = ProviderBar(project, { statusLabel.text = it }, toolsButton.component)

        private val agentBanner = AgentBanner(
            onOpenParent = { openChat(it) },
            onOpenSpec = { openAgentFile() },
        )

        private val changesBarPanel = ChangesBar(project, session, { statusLabel.text = it }) {
            composer.revalidate()
            composer.repaint()
        }

        private val chatAttachments = ChatAttachments(project, { statusLabel.text = it }) {
            composer.revalidate()
            composer.repaint()
        }

        private val agentReturns = AgentReturnsBar(project, { openAgentFile(it) }) {
            composer.revalidate()
            composer.repaint()
        }

        private val turnCostTracker = TurnCostTracker()

        private val sessionListener = ChangeSessionService.Listener { count -> changesBarPanel.update(count) }


        private val meterRow = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(10), 0)).apply {
            isOpaque = false
            add(contextLabel)
            add(usageLabel)
        }

        private val statusRow = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(statusLabel, BorderLayout.CENTER)
            add(meterRow, BorderLayout.EAST)
        }

        private val inputCard = RoundedPanel(
            BorderLayout(0, JBUI.scale(4)),
            fill = { UIUtil.getTextFieldBackground() },
            stroke = { if (input.hasFocus()) ChatColors.accent else ChatColors.separator },
        ).apply {
            border = JBUI.Borders.empty(JBUI.scale(7), JBUI.scale(8))
            add(
                JBScrollPane(input).apply {
                    border = JBUI.Borders.empty()
                    viewportBorder = JBUI.Borders.empty()
                    background = UIUtil.getTextFieldBackground()
                    viewport.background = UIUtil.getTextFieldBackground()
                    horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                    verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                },
                BorderLayout.CENTER,
            )
            add(
                JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
                    isOpaque = false
                    add(attachButton)
                    add(sendButton)
                },
                BorderLayout.SOUTH,
            )
        }

        private val composerTop: JPanel = JPanel(BorderLayout(0, JBUI.scale(5))).apply {
            isOpaque = false
            add(
                JPanel(BorderLayout(0, JBUI.scale(5))).apply {
                    isOpaque = false
                    add(changesBarPanel.component, BorderLayout.NORTH)
                    add(agentReturns.component, BorderLayout.SOUTH)
                },
                BorderLayout.NORTH,
            )
            add(chatAttachments.component, BorderLayout.CENTER)
        }

        private val composer: JPanel = JPanel(BorderLayout(0, JBUI.scale(5))).apply {
            isOpaque = false
            border = BorderFactory.createCompoundBorder(
                JBUI.Borders.customLineTop(ChatColors.separator),
                JBUI.Borders.empty(9, 12, 10, 12),
            )
            add(composerTop, BorderLayout.NORTH)
            add(inputCard, BorderLayout.CENTER)
            add(statusRow, BorderLayout.SOUTH)
        }

        private val header: JPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(providerBar.component, BorderLayout.NORTH)
            add(agentBanner.component, BorderLayout.SOUTH)
        }

        val component: JComponent = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = ChatColors.background
            add(header, BorderLayout.NORTH)
            add(transcript.component, BorderLayout.CENTER)
            add(composer, BorderLayout.SOUTH)
        }

        init {
            sendButton.addActionListener { send() }
            input.addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (e.keyCode != KeyEvent.VK_ENTER) return
                    if (e.isShiftDown) {
                        e.consume()
                        input.replaceSelection("\n")
                    } else {
                        e.consume()
                        send()
                    }
                }
            })
            input.addFocusListener(object : FocusAdapter() {
                override fun focusGained(e: FocusEvent) = inputCard.repaint()
                override fun focusLost(e: FocusEvent) = inputCard.repaint()
            })

            AgentMentionPopup(project, input) { beginHandoff(it) }.install()
            SkillMentionPopup(project, input, { conversationTools.activeSkills }) { addSkill(it) }.install()

            session.addListener(sessionListener)
            changesBarPanel.update(session.changedFileCount)
            providerBar.seedFromDefault()
            applyConversationTools(defaultConversationTools())
            restoreLastChat()
        }

        fun refreshProviderBar() = providerBar.refresh()

        override fun dispose() {
            panels.remove(project)
            session.removeListener(sessionListener)
            saveCurrentChat(active = true, background = false)
        }


        private fun restoreLastChat() {
            if (isOpened(project)) restoreLastChatNow()
        }

        fun restoreLastChatNow() {
            if (!restoreStarted.compareAndSet(false, true)) return
            ApplicationManager.getApplication().executeOnPooledThread {
                val chat = chatHistory.activeId()?.let { chatHistory.load(it) } ?: return@executeOnPooledThread
                ApplicationManager.getApplication().invokeLater({
                    if (history.isEmpty() && rows.isEmpty()) applyChat(chat)
                }, project.disposed)
            }
        }

        fun startNewChat() {
            if (!sendButton.isEnabled) return
            saveCurrentChat(active = false)
            resetConversation()
            statusLabel.text = " "
            input.text = ""
        }

        fun showHistory(e: AnActionEvent) {
            ChatHistoryPopup.show(
                project = project,
                service = chatHistory,
                chats = chatHistory.chats(),
                currentId = chatId,
                dataContext = e.dataContext,
                onOpen = ::openChat,
                onCurrentDeleted = { resetConversation() },
            )
        }

        private fun openChat(id: String) {
            if (id == chatId) return
            if (!sendButton.isEnabled) {
                statusLabel.text = "Wait for the current reply to finish before switching chats."
                return
            }

            val outgoing = snapshot()
            ApplicationManager.getApplication().executeOnPooledThread {
                if (outgoing != null) chatHistory.save(outgoing, active = false)
                val chat = chatHistory.load(id)
                ApplicationManager.getApplication().invokeLater({
                    if (chat == null) showError("That chat could not be opened.") else applyChat(chat)
                }, project.disposed)
            }
        }

        private fun applyChat(chat: StoredChat) {
            resetConversation(chat.id, chat.createdAt)
            history.addAll(chat.messages)
            rows.addAll(chat.transcript)
            applyAgent(chat.agent)
            applyConversationTools(chat.conversationTools ?: defaultConversationTools())
            agentReturns.set(chat.returns.orEmpty())
            providerBar.setSelection(chat.configurationName, chat.model)
            setUsage(chat.usage ?: SessionUsage())
            contextMeter.restore(chat.context)
            setContext(
                if (contextMeter.anchor > 0) contextMeter.estimate(history, overheadChars = 0) else 0,
                contextWindowTokens(),
            )
            rows.forEachIndexed { index, row -> render(index, row) }
            ApplicationManager.getApplication().executeOnPooledThread { chatHistory.setActiveId(chat.id) }
        }

        private fun applyAgent(session: AgentSession?, known: AgentDefinition? = null) {
            agentSession = session
            activeAgent = session?.let {
                known ?: AgentCatalog.find(project, it.agentName) ?: AgentCatalog.placeholderFor(it.agentName)
            }
            agentBanner.show(session, toolsDescription())
            transcript.onReturnSummary = if (session?.parentChatId == null) null else ::returnSummary
        }

        private fun resetConversation(
            id: String = ChatHistoryService.newChatId(),
            createdAt: Long = System.currentTimeMillis(),
        ) {
            chatId = id
            chatCreatedAt = createdAt
            history.clear()
            rows.clear()
            runningTool = null
            runningToolIndex = -1
            withheldCards.clear()
            applyAgent(null)
            applyConversationTools(defaultConversationTools())
            shellTool?.forgetApprovals()
            mcp.forgetApprovals()
            raisedMaxTokens = 0
            providerBar.seedFromDefault()
            setUsage(SessionUsage())
            contextMeter.reset()
            setContext(0, 0)
            beginTurnCost()
            transcript.clear()
            chatAttachments.clear()
            agentReturns.clear()
            invokedSkills.clear()
        }

        private fun snapshot(): StoredChat? {
            if (history.isEmpty() || rows.isEmpty()) return null
            return StoredChat(
                id = chatId,
                title = ChatHistoryService.titleFor(rows),
                createdAt = chatCreatedAt,
                updatedAt = System.currentTimeMillis(),
                messages = history.toList(),
                transcript = rows.toList(),
                usage = usage,
                context = contextMeter.snapshot(),
                configurationName = providerBar.configurationName,
                model = providerBar.modelName,
                agent = agentSession,
                returns = agentReturns.returns(),
                conversationTools = conversationTools.takeIf { it.isCustom },
            )
        }

        private fun contextWindowTokens(): Int =
            AgentConfigurations.getInstance(project)
                .resolve(providerBar.configurationName, providerBar.modelName).contextWindowTokens

        private fun saveCurrentChat(active: Boolean, background: Boolean = true) {
            val chat = runCatching { snapshot() }.getOrNull()
            val leaving = chatId

            val write = Runnable {
                when {
                    chat != null -> chatHistory.save(chat, active)
                    !active && chatHistory.activeId() == leaving -> chatHistory.setActiveId(null)
                }
            }
            if (background) ApplicationManager.getApplication().executeOnPooledThread(write) else write.run()
        }


        private fun showUserMessage(markdown: String) {
            rows += StoredRow(StoredRow.USER, text = markdown)
            transcript.addUserMessage(markdown)
        }

        private fun showAssistantMessage(markdown: String) {
            rows += StoredRow(StoredRow.ASSISTANT, text = markdown)
            transcript.addAssistantMessage(markdown)
        }

        private fun showThinking(summary: String) {
            val headline = headlineOf(summary)
            rows += StoredRow(StoredRow.THINKING, summary = headline, details = summary)
            transcript.addToolCall("", "thinking", headline, summary, ChatTranscript.ToolStatus.DONE)
        }

        private fun headlineOf(summary: String): String {
            val line = summary.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
            return if (line.length <= 90) line else line.take(89).trimEnd() + "…"
        }

        private fun showCompaction(result: HistoryCompaction.Result) {
            val what = buildList {
                if (result.evicted > 0) add("${result.evicted} old tool result(s)")
                if (result.summarized) add("${result.summarizedMessages} earlier message(s)")
            }.joinToString(" and ")
            val summary = "freed ~${formatTokens(result.freedTokens)} tokens from $what"
            val details = buildString {
                append("The conversation had grown to roughly ${"%,d".format(result.beforeTokens)} tokens, ")
                if (result.evicted > 0) {
                    append("so the output of the ${result.evicted} oldest tool call(s) was replaced with a note ")
                    append("saying what had been there")
                }
                if (result.summarized) {
                    if (result.evicted > 0) append(", and that was not enough on its own, so ")
                    else append("and none of it was old tool output that could simply be dropped, so ")
                    append("the model was asked to summarise the ${result.summarizedMessages} oldest ")
                    append("message(s) and the summary was put in their place")
                }
                append(", bringing it to about ${"%,d".format(result.afterTokens)}.\n\n")
                if (result.summarized) {
                    append("Summarising cost an extra request, and it is lossy: exact file contents, ")
                    append("line numbers and wording from the early part of the conversation are no longer ")
                    append("available to the model, only the summary's description of them. ")
                } else {
                    append("Only tool output was dropped, and only from the older part of the conversation -- ")
                    append("nothing you or the model wrote, and nothing from the last few calls. ")
                }
                append("This chat still shows all of it; it is the copy sent with each request that shrank.\n\n")
                append("The threshold is the context window on the settings page.")
            }
            rows += StoredRow(StoredRow.TOOL, name = "compact_context", summary = summary, details = details)
            transcript.addToolCall("", "compact_context", summary, details, ChatTranscript.ToolStatus.DONE)
        }

        private fun showToolCall(
            requestId: String,
            name: String,
            summary: String,
            details: String,
            status: ChatTranscript.ToolStatus,
        ): ChatTranscript.RunningTool {
            rows += StoredRow(
                StoredRow.TOOL,
                name = name,
                summary = summary,
                details = details,
                status = stored(status),
                requestId = requestId,
            )
            return transcript.addToolCall(requestId, name, summary, details, status)
        }

        private fun startToolCall(requestId: String, name: String, summary: String, details: String) {
            rows += StoredRow(
                StoredRow.TOOL,
                name = name,
                summary = summary,
                details = details,
                requestId = requestId,
            )
            runningToolIndex = rows.lastIndex
            runningTool = transcript.startToolCall(requestId, name, summary, details)
        }

        private fun finishToolCall(
            details: String,
            status: ChatTranscript.ToolStatus,
        ): ChatTranscript.RunningTool? {
            val row = runningTool ?: return null
            runningTool = null
            val index = runningToolIndex
            runningToolIndex = -1

            row.finish(details, status)
            rows.getOrNull(index)?.let { rows[index] = it.copy(details = details, status = stored(status)) }
            return row
        }

        private fun stored(status: ChatTranscript.ToolStatus): String? = when (status) {
            ChatTranscript.ToolStatus.FAILED -> StoredRow.FAILED
            ChatTranscript.ToolStatus.CANCELLED -> StoredRow.CANCELLED
            else -> null
        }

        private fun toolStatus(saved: String?): ChatTranscript.ToolStatus = when (saved) {
            StoredRow.FAILED -> ChatTranscript.ToolStatus.FAILED
            StoredRow.CANCELLED -> ChatTranscript.ToolStatus.CANCELLED
            else -> ChatTranscript.ToolStatus.DONE
        }

        private fun showError(message: String) {
            rows += StoredRow(StoredRow.ERROR, text = message)
            transcript.addError(message)
        }

        private fun render(index: Int, row: StoredRow) {
            when (row.kind) {
                StoredRow.HANDOFF -> row.handoff?.let { showHandoff(index, it, row.summary) }
                StoredRow.USER -> transcript.addUserMessage(row.text)
                StoredRow.ASSISTANT -> transcript.addAssistantMessage(row.text)
                StoredRow.TOOL ->
                    transcript.addToolCall(
                        row.requestId.orEmpty(), row.name, row.summary, row.details, toolStatus(row.status),
                    )
                StoredRow.THINKING ->
                    transcript.addToolCall("", "thinking", row.summary, row.details, ChatTranscript.ToolStatus.DONE)
                StoredRow.COST -> row.text.toBigDecimalOrNull()?.let {
                    transcript.setTurnCost(
                        TurnCostTracker.label(it),
                        TurnCostTracker.tooltip(row.name, row.summary.toIntOrNull() ?: 1),
                    )
                }
                else -> transcript.addError(row.text)
            }
        }


        private fun beginHandoff(definition: AgentDefinition) {
            if (!sendButton.isEnabled) {
                statusLabel.text = "Wait for the current reply to finish before handing over."
                return
            }

            val reply = transcript.lastTurnMarkdown()
            val path = AgentFiles.spec(project, definition, reply)
            if (path == null) {
                showError("The specification for @${definition.name} could not be written to .cache.")
                return
            }

            val handoff = AgentHandoff(definition.name, path.toString(), chatId)
            val index = rows.size
            rows += StoredRow(
                StoredRow.HANDOFF,
                name = definition.name,
                summary = definition.description,
                text = AgentFiles.displayPath(project, path),
                handoff = handoff,
            )
            showHandoff(index, handoff, definition.description)
            AgentFiles.open(project, path)
            saveCurrentChat(active = true)
            statusLabel.text = if (reply.isBlank()) {
                "There was no closing reply to draft from, so the spec starts from @${definition.name}'s template."
            } else {
                "Edit the spec, then press Proceed to start @${definition.name}."
            }
        }

        private fun showHandoff(index: Int, handoff: AgentHandoff, description: String) {
            val path = Path.of(handoff.specPath)
            transcript.addHandoff(
                agentName = handoff.agentName,
                description = description,
                specName = AgentFiles.displayPath(project, path),
                state = handoff.state,
                onOpenSpec = { openAgentFile(path) },
                onProceed = { proceedWithHandoff(index) },
                onCancel = { cancelHandoff(index) },
                onOpenChat = { rows.getOrNull(index)?.handoff?.childChatId?.let { openChat(it) } },
            )
        }

        private fun proceedWithHandoff(index: Int): Boolean {
            val stored = rows.getOrNull(index) ?: return false
            val handoff = stored.handoff?.takeIf { !it.isSettled } ?: return false
            if (!sendButton.isEnabled) {
                statusLabel.text = "Wait for the current reply to finish before handing over."
                return false
            }

            val definition = AgentCatalog.find(project, handoff.agentName)
            if (definition == null) {
                showError("@${handoff.agentName} is no longer defined, so nothing was handed over.")
                return false
            }

            val path = Path.of(handoff.specPath)
            val spec = AgentFiles.read(path)?.takeIf { it.isNotBlank() }
            if (spec == null) {
                showError(
                    "${AgentFiles.displayPath(project, path)} is empty or could not be read, " +
                        "so nothing was handed over.",
                )
                return false
            }

            val childId = ChatHistoryService.newChatId()
            val session = AgentSession(definition.name, chatId, handoff.specPath)
            rows[index] = stored.copy(
                handoff = handoff.copy(childChatId = childId, state = AgentHandoff.PROCEEDED),
            )
            saveCurrentChat(active = false)
            startAgentChat(childId, definition, session, spec, path)
            return true
        }

        private fun cancelHandoff(index: Int) {
            val stored = rows.getOrNull(index) ?: return
            val handoff = stored.handoff?.takeIf { !it.isSettled } ?: return
            rows[index] = stored.copy(handoff = handoff.copy(state = AgentHandoff.CANCELLED))
            saveCurrentChat(active = true)
            statusLabel.text = "The hand-off to @${handoff.agentName} was dropped."
        }

        private fun startAgentChat(
            childId: String,
            definition: AgentDefinition,
            session: AgentSession,
            spec: String,
            path: Path,
        ) {
            resetConversation(childId)
            applyAgent(session, definition)
            applyConversationTools(
                ConversationTools(skills = definition.skills.ifEmpty { defaultConversationTools().skills }),
            )
            providerBar.setSelection(definition.configurationName, definition.model)
            input.text = ""
            chatAttachments.clear()

            val displayPath = AgentFiles.displayPath(project, path)
            history.add(ChatMessage.text("user", spec))
            showUserMessage("**Specification for @${definition.name}** — `$displayPath`\n\n$spec")
            startTurn(0)
            statusLabel.text = "@${definition.name} is working from $displayPath."
        }

        private fun openAgentFile(path: Path? = agentSession?.specPath?.let { Path.of(it) }) {
            if (path == null) return
            if (!AgentFiles.open(project, path)) showError("$path could not be opened.")
        }

        private fun returnSummary(markdown: String): Boolean {
            val session = agentSession ?: return false
            val parentChatId = session.parentChatId ?: return false

            val path = AgentFiles.summary(project, session.agentName, markdown)
            if (path == null) {
                showError("The summary could not be written to .cache.")
                return false
            }

            val returned = AgentReturn(session.agentName, chatId, path.toString())
            if (!chatHistory.addReturn(parentChatId, returned)) {
                showError("The chat that started this agent is no longer in the history, so nothing was returned.")
                return false
            }

            AgentFiles.open(project, path)
            statusLabel.text =
                "Returned to the chat that started this agent — edit ${AgentFiles.displayPath(project, path)} " +
                    "there before sending it."
            return true
        }

        /*
         * What a /name adds to the message it precedes. The skill's own file is where the
         * procedure is, so this says where to find it rather than repeating any of it, and it goes
         * ahead of the user's text so the model reads the skill before deciding anything.
         */
        private fun invokedSkillBlocks(): List<PrependedBlock> = invokedSkills.map { skill ->
            val path = SkillCatalog.displayPath(project, skill.file)
            PrependedBlock(
                marker = "🧭 `/${skill.name}`",
                body = "The user invoked the skill \"${skill.name}\" for this message by typing " +
                    "/${skill.name}. Its instructions are in `$path`: read that file first and " +
                    "follow it for this request, rather than working from the description of it " +
                    "you were given. If it turns out not to fit what is being asked, say so " +
                    "instead of quietly ignoring it.",
            )
        }

        private data class PrependedBlock(val marker: String, val body: String)

        private fun returnedBlocks(): List<PrependedBlock> {
            val pending = agentReturns.returns()
            if (pending.isEmpty()) return emptyList()

            val blocks = mutableListOf<PrependedBlock>()
            val unreadable = mutableListOf<String>()
            for (returned in pending) {
                val path = Path.of(returned.path)
                val name = AgentFiles.displayPath(project, path)
                val text = AgentFiles.read(path)?.takeIf { it.isNotBlank() }
                if (text == null) {
                    unreadable += name
                    continue
                }
                blocks += PrependedBlock(
                    marker = "📥 `@${returned.agentName} — $name`",
                    body = "A summary returned by @${returned.agentName}, from the chat that ran it:\n\n$text",
                )
            }
            if (unreadable.isNotEmpty()) {
                showError("Nothing could be read from ${unreadable.joinToString(", ")}.")
            }
            return blocks
        }

        private fun send() {
            if (!sendButton.isEnabled) return
            val text = input.text.trim()
            val attachments = chatAttachments.attachments()
            val prepended = returnedBlocks() + invokedSkillBlocks() +
                attachments.map { PrependedBlock("📎 `${it.summary}`", it.body) }
            if (text.isEmpty() && prepended.isEmpty()) return

            val attached = prepended.joinToString("\n\n") { it.body }
            val messageText = when {
                prepended.isEmpty() -> text
                text.isEmpty() -> attached
                else -> "$attached\n\n$text"
            }

            val markers = if (prepended.size == 1) {
                prepended.first().marker
            } else {
                prepended.joinToString("\n") { "- ${it.marker}" }
            }
            val displayText = when {
                prepended.isEmpty() -> text
                text.isEmpty() -> markers
                else -> "$markers\n\n$text"
            }

            val sizeBeforeTurn = history.size
            history.add(ChatMessage.text("user", messageText))
            showUserMessage(displayText)
            input.text = ""
            chatAttachments.clear()
            agentReturns.clear()
            invokedSkills.clear()

            startTurn(sizeBeforeTurn)
        }

        private fun startTurn(sizeBeforeTurn: Int) {
            setBusy(true)
            transcript.clearRequestFailure()
            settleApprovals()

            val configuration = AgentConfigurations.getInstance(project)
                .resolve(providerBar.configurationName, providerBar.modelName)
            val model = configuration.model
            val maxTokens = maxOf(configuration.maxTokens, raisedMaxTokens)
            val maxIterations = AICodingAgentSettingsState.getInstance().state.maxIterations
            val maxToolOutputTokens = AICodingAgentSettingsState.getInstance().state.maxToolOutputTokens
            val contextWindow = configuration.contextWindowTokens
            val conversationId = chatId
            val agentPrompt = activeAgent?.prompt.orEmpty()

            summarizeTool?.bind(agentTools(), conversationId, providerBar.configurationName)

            cancelled.set(false)
            beginTurnCost()

            turn = ApplicationManager.getApplication().executeOnPooledThread {
                val endpoint = AICodingAgentEndpoint.from(configuration)
                if (endpoint.token.isBlank()) {
                    ApplicationManager.getApplication().invokeLater {
                        val dropped = history.drop(sizeBeforeTurn)
                        rollbackHistoryTo(sizeBeforeTurn)
                        setBusy(false)
                        offerRetry(sizeBeforeTurn, dropped)
                        ChatDialogs.promptForMissingApiKey(project, configuration)
                    }
                    return@executeOnPooledThread
                }

                try {
                    val reasoning = ReasoningOptions.from(configuration)
                    agent.run(endpoint, model, maxTokens, maxIterations, contextWindow, history, object : AICodingAgent.Listener {
                        override fun onAssistantText(text: String) {
                            if (text.isBlank()) return
                            ApplicationManager.getApplication().invokeLater { showAssistantMessage(text) }
                        }

                        override fun onThinking(summary: String) {
                            ApplicationManager.getApplication().invokeLater { showThinking(summary) }
                        }

                        override fun onUsage(usage: AICodingAgentUsage) {
                            ApplicationManager.getApplication().invokeLater {
                                addUsage(usage)
                                addTurnCost(model, usage)
                            }
                        }

                        override fun onCompacted(result: HistoryCompaction.Result) {
                            ApplicationManager.getApplication().invokeLater { showCompaction(result) }
                        }

                        override fun onContext(usedTokens: Int, windowTokens: Int) {
                            ApplicationManager.getApplication().invokeLater {
                                setContext(usedTokens, windowTokens)
                            }
                        }

                        override fun onToolStarted(
                            call: AICodingAgent.ToolCallId,
                            name: String,
                            input: JsonObject,
                            interruptible: Boolean,
                        ) {
                            ApplicationManager.getApplication().invokeLater {
                                transcript.setCancellable(interruptible)
                                startToolCall(
                                    call.requestId, name,
                                    ToolSummary.summarize(input), ToolSummary.details(input, ""),
                                )
                            }
                        }

                        override fun onToolCall(
                            call: AICodingAgent.ToolCallId,
                            name: String,
                            input: JsonObject,
                            result: String,
                            outcome: AICodingAgent.ToolOutcome,
                        ) {
                            ApplicationManager.getApplication().invokeLater {
                                transcript.setCancellable(true)
                                val status = when (outcome) {
                                    AICodingAgent.ToolOutcome.OK -> ChatTranscript.ToolStatus.DONE
                                    AICodingAgent.ToolOutcome.FAILED -> ChatTranscript.ToolStatus.FAILED
                                    AICodingAgent.ToolOutcome.CANCELLED -> ChatTranscript.ToolStatus.CANCELLED
                                    AICodingAgent.ToolOutcome.TOO_LARGE -> ChatTranscript.ToolStatus.FAILED
                                }
                                val details = ToolSummary.details(input, result)
                                val card = if (runningTool != null) {
                                    finishToolCall(details, status)
                                } else {
                                    showToolCall(call.requestId, name, ToolSummary.summarize(input), details, status)
                                }
                                if (outcome == AICodingAgent.ToolOutcome.TOO_LARGE && card != null) {
                                    withheldCards[call.toolUseId] = card
                                }
                            }
                        }

                        override fun onMaxTokens(limit: Int, suggested: Int): Int? {
                            var next: Int? = null
                            ApplicationManager.getApplication().invokeAndWait {
                                next = askToContinue(limit, suggested)
                            }
                            return next
                        }

                        override fun onMaxIterations(used: Int): Boolean {
                            if (cancelled.get()) return false
                            var extend = false
                            ApplicationManager.getApplication().invokeAndWait {
                                extend = askToExtendIterations(used)
                            }
                            return extend
                        }

                        override fun onToolOutputTooLarge(
                            name: String,
                            toolUseId: String,
                            output: String,
                            tokens: Int,
                            limit: Int,
                        ) {
                            ApplicationManager.getApplication().invokeLater {
                                offerApproval(name, toolUseId, output, tokens, limit)
                            }
                        }
                    }, isCancelled = cancelled::get, reasoning = reasoning, conversationId = conversationId,
                        maxToolOutputTokens = maxToolOutputTokens, meter = contextMeter,
                        agentPrompt = agentPrompt)
                    ApplicationManager.getApplication().invokeLater { endTurn() }
                } catch (e: Throwable) {
                    log.warn("The turn failed", e)
                    val message = if (cancelled.get()) null else e.message ?: "The request failed."
                    ApplicationManager.getApplication().invokeLater {
                        if (message != null) {
                            runCatching {
                                val dropped = if (history.size <= sizeBeforeTurn + 1) {
                                    history.drop(sizeBeforeTurn).also { rollbackHistoryTo(sizeBeforeTurn) }
                                } else {
                                    emptyList()
                                }
                                showError(
                                    if (dropped.isEmpty()) "Sending the last round of tool results failed: $message"
                                    else message,
                                )
                                offerRetry(sizeBeforeTurn, dropped)
                            }.onFailure { log.warn("Could not report the failure in the transcript", it) }
                        }
                        endTurn()
                    }
                }
            }
        }

        private fun offerRetry(sizeBeforeTurn: Int, dropped: List<ChatMessage>) {
            transcript.markRequestFailed { retryTurn(sizeBeforeTurn, dropped) }
        }

        private fun retryTurn(sizeBeforeTurn: Int, dropped: List<ChatMessage>) {
            if (!sendButton.isEnabled) return

            if (dropped.isNotEmpty() && history.size == sizeBeforeTurn) {
                history.addAll(dropped)
                startTurn(sizeBeforeTurn)
            } else {
                startTurn(history.size)
            }
        }

        private fun cancelTurn() {
            if (sendButton.isEnabled) return
            if (!cancelled.compareAndSet(false, true)) return

            turn?.cancel(true)
            showError("Stopped. The reply is incomplete.")
            setBusy(false)
        }

        private fun offerApproval(toolName: String, toolUseId: String, output: String, tokens: Int, limit: Int) {
            showError(
                "Stopped: $toolName returned ${"%,d".format(tokens)} tokens, over the " +
                    "${"%,d".format(limit)}-token limit, so it was not sent to the model.",
            )
            transcript.setTurnContinuable(true)

            val card = withheldCards[toolUseId] ?: return
            val file = WithheldOutput.save(project, toolName, output)
            card.offerApproval(
                tokens = tokens,
                limit = limit,
                onApprove = { approveWithheldOutput(toolName, toolUseId, output, file) },
                onEdit = file?.let { path -> { openWithheldOutput(path) } },
            )
        }

        private fun openWithheldOutput(file: Path) {
            if (!WithheldOutput.open(project, file)) {
                showError("$file could not be opened.")
            }
        }

        private fun approveWithheldOutput(
            toolName: String,
            toolUseId: String,
            output: String,
            file: Path?,
        ): Boolean {
            if (!sendButton.isEnabled) {
                showError("A reply is already running, so that output was not sent.")
                return false
            }

            val text = file?.let { WithheldOutput.read(it) }?.takeIf { it.isNotBlank() } ?: output
            if (!ToolResults.replace(history, toolUseId, text)) {
                showError("That tool call is no longer in the conversation, so the output was not sent.")
                return false
            }

            withheldCards.remove(toolUseId)
            showError(
                "Approved $toolName's output (${"%,d".format(TokenCounter.count(text))} tokens) " +
                    "as the result of that call. Press Continue to send it.",
            )
            return true
        }

        private fun continueTurn() {
            if (!sendButton.isEnabled) return
            startTurn(history.size)
        }

        private fun settleApprovals() {
            withheldCards.values.forEach { it.closeApproval("Sent as withheld") }
            withheldCards.clear()
            transcript.setTurnContinuable(false)
        }

        private fun setBusy(busy: Boolean) {
            sendButton.isEnabled = !busy
            transcript.setThinking(busy)
            statusLabel.text = " "
        }

        private fun endTurn() {
            finishToolCall(rows.getOrNull(runningToolIndex)?.details.orEmpty(), ChatTranscript.ToolStatus.FAILED)
            turnCostTracker.take()?.let { snapshot ->
                rows += StoredRow(
                    StoredRow.COST,
                    text = snapshot.cost.toPlainString(),
                    name = snapshot.model,
                    summary = snapshot.requests.toString(),
                )
            }
            transcript.endAiTurn()
            setBusy(false)
            saveCurrentChat(active = true)
        }


        private fun beginTurnCost() {
            turnCostTracker.begin()
            transcript.setTurnCost(null)
        }

        private fun addTurnCost(model: String, reported: AICodingAgentUsage) {
            val total = turnCostTracker.add(model, reported) ?: return
            transcript.setTurnCost(
                TurnCostTracker.label(total),
                TurnCostTracker.tooltip(turnCostTracker.model, turnCostTracker.requests),
            )
        }


        private fun addUsage(reported: AICodingAgentUsage) = setUsage(usage + reported)

        private fun setUsage(total: SessionUsage) {
            usage = total
            usageLabel.text = if (total.isEmpty) "" else buildString {
                append("↑ ${formatTokens(total.totalInputTokens)}   ↓ ${formatTokens(total.outputTokens)}")
                total.cacheHitRate?.let { append("   ⚡ ${(it * 100).roundToInt()}%") }
            }
            usageLabel.toolTipText = if (total.isEmpty) null else buildString {
                append("<html>Tokens used in this chat, over ${total.requests} request(s):<br><br>")
                append("Input, billed in full: ${"%,d".format(total.inputTokens)}<br>")
                append("Written to the cache: ${"%,d".format(total.cacheWriteTokens)}<br>")
                append("Read from the cache: ${"%,d".format(total.cacheReadTokens)}<br>")
                append("Input in total: ${"%,d".format(total.totalInputTokens)}<br>")
                append("Output: ${"%,d".format(total.outputTokens)}<br><br>")
                append("A request that failed is not counted.</html>")
            }
        }

        private fun setContext(used: Int, window: Int) {
            if (used <= 0) {
                contextLabel.text = ""
                contextLabel.toolTipText = null
                return
            }

            val share = if (window > 0) used.toDouble() / window else null
            contextLabel.text = buildString {
                append("◱ ${formatTokens(used)}")
                if (window > 0) append(" / ${formatTokens(window)}")
                share?.let { append("  ${(it * 100).roundToInt()}%") }
            }
            contextLabel.foreground = when {
                share == null -> ChatColors.muted
                share >= CONTEXT_FULL -> ChatColors.error
                share >= HistoryCompaction.COMPACT_ABOVE -> ChatColors.warning
                else -> ChatColors.muted
            }
            contextLabel.toolTipText = buildString {
                append("<html>Context in use: ${"%,d".format(used)} tokens")
                if (window > 0) append(" of ${"%,d".format(window)}")
                append(".<br><br>")
                append("What the next request would send: the system prompt, the tool ")
                append("descriptions and the whole conversation. Unlike the token counts beside ")
                append("it, this is not a running total -- it falls when the history is ")
                append("compacted.<br><br>")
                if (contextMeter.anchor > 0) {
                    append("Measured from what the provider counted for the last request, plus an ")
                    append("estimate of what has been added since.")
                } else {
                    append("Estimated: no request has been counted for this conversation yet.")
                }
                if (window > 0) {
                    val at = (HistoryCompaction.COMPACT_ABOVE * 100).roundToInt()
                    append("<br><br>Older tool output starts being dropped above $at%.")
                }
                append("</html>")
            }
        }


        private fun askToContinue(limit: Int, suggested: Int): Int? {
            val next = if (suggested > limit) ChatDialogs.confirmContinue(project, limit, suggested)
                       else ChatDialogs.askForMaxTokens(project, limit)
            if (next != null) raisedMaxTokens = next
            showError(
                when {
                    next == null -> "Response hit the max_tokens limit and is incomplete."
                    next != limit -> "Response hit the $limit-token limit — continuing at $next."
                    else -> "Response hit the max_tokens limit — continuing."
                }
            )
            return next
        }

        private fun askToExtendIterations(used: Int): Boolean {
            val extend = ChatDialogs.confirmExtendIterations(project, used)
            showError(
                if (extend) "Tool-call limit reached — continuing."
                else "Stopped after $used rounds of tool calls. The reply is incomplete."
            )
            return extend
        }


        fun startSideChat(code: String, displayPath: String, extension: String, lineRange: String) {
            startNewChat()
            chatAttachments.startSideChat(code, displayPath, extension, lineRange)
            input.requestFocusInWindow()
        }


        private fun rollbackHistoryTo(size: Int) {
            while (history.size > size) history.removeAt(history.lastIndex)
        }

        private companion object {
            private const val CONTEXT_FULL = 0.85

            fun formatTokens(count: Int): String = when {
                count < 1_000 -> count.toString()
                count < 1_000_000 -> "%.1fk".format(count / 1_000.0)
                else -> "%.2fM".format(count / 1_000_000.0)
            }
        }
    }
}
