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
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgent
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentEndpoint
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
import com.github.piotrszybicki.independentintelijaiplugin.skills.SkillCatalog
import com.github.piotrszybicki.independentintelijaiplugin.tools.ProjectEnvironment
import com.github.piotrszybicki.independentintelijaiplugin.tools.RunShellCommandTool
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
        private val log = Logger.getInstance(ChatToolWindowFactory::class.java)

        private val agent = AICodingAgent(
            tools = { ChangeTrackingTool.wrapAll(ToolCatalog.enabledIn(builtInTools) + mcp.tools(), session) },
            environment = { ProjectEnvironment.describe(project) },
            skills = { SkillCatalog.describe(project) },
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

        // --- extracted components -----------------------------------------------------------------

        private val providerBar = ProviderBar(project) { statusLabel.text = it }

        private val changesBarPanel = ChangesBar(project, session, { statusLabel.text = it }) {
            composer.revalidate()
            composer.repaint()
        }

        private val chatAttachments = ChatAttachments(project, { statusLabel.text = it }) {
            composer.revalidate()
            composer.repaint()
        }

        private val turnCostTracker = TurnCostTracker()

        private val sessionListener = ChangeSessionService.Listener { count -> changesBarPanel.update(count) }

        // --- layout ------------------------------------------------------------------------------

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
            add(changesBarPanel.component, BorderLayout.NORTH)
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

        val component: JComponent = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = ChatColors.background
            add(providerBar.component, BorderLayout.NORTH)
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

            session.addListener(sessionListener)
            changesBarPanel.update(session.changedFileCount)
            providerBar.seedFromDefault()
            restoreLastChat()
        }

        fun refreshProviderBar() = providerBar.refresh()

        override fun dispose() {
            panels.remove(project)
            session.removeListener(sessionListener)
            saveCurrentChat(active = true, background = false)
        }

        // --- chat history ------------------------------------------------------------------------

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
            providerBar.setSelection(chat.configurationName, chat.model)
            setUsage(chat.usage ?: SessionUsage())
            contextMeter.restore(chat.context)
            setContext(
                if (contextMeter.anchor > 0) contextMeter.estimate(history, overheadChars = 0) else 0,
                contextWindowTokens(),
            )
            chat.transcript.forEach { render(it) }
            ApplicationManager.getApplication().executeOnPooledThread { chatHistory.setActiveId(chat.id) }
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

        // --- transcript --------------------------------------------------------------------------

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

        private fun render(row: StoredRow) {
            when (row.kind) {
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

        // --- send / turn lifecycle ---------------------------------------------------------------

        private fun send() {
            if (!sendButton.isEnabled) return
            val text = input.text.trim()
            val attachments = chatAttachments.attachments()
            if (text.isEmpty() && attachments.isEmpty()) return

            val attached = attachments.joinToString("\n\n") { it.body }
            val messageText = when {
                attachments.isEmpty() -> text
                text.isEmpty() -> attached
                else -> "$attached\n\n$text"
            }

            val markers = if (attachments.size == 1) {
                "📎 `${attachments.first().summary}`"
            } else {
                attachments.joinToString("\n") { "- 📎 `${it.summary}`" }
            }
            val displayText = when {
                attachments.isEmpty() -> text
                text.isEmpty() -> markers
                else -> "$markers\n\n$text"
            }

            val sizeBeforeTurn = history.size
            history.add(ChatMessage.text("user", messageText))
            showUserMessage(displayText)
            input.text = ""
            chatAttachments.clear()

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
                        maxToolOutputTokens = maxToolOutputTokens, meter = contextMeter)
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

        // --- turn cost ---------------------------------------------------------------------------

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

        // --- token usage -------------------------------------------------------------------------

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

        // --- dialogs -----------------------------------------------------------------------------

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

        // --- attachments -------------------------------------------------------------------------

        fun startSideChat(code: String, displayPath: String, extension: String, lineRange: String) {
            startNewChat()
            chatAttachments.startSideChat(code, displayPath, extension, lineRange)
            input.requestFocusInWindow()
        }

        // --- helpers -----------------------------------------------------------------------------

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
