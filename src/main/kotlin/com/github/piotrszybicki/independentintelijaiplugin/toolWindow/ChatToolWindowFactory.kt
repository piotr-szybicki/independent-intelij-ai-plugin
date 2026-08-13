package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupManager
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.util.Disposer
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
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.HistoryCompaction
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.ReasoningOptions
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.SessionUsage
import com.github.piotrszybicki.independentintelijaiplugin.changes.ChangeSessionService
import com.github.piotrszybicki.independentintelijaiplugin.changes.ChangeTrackingTool
import com.github.piotrszybicki.independentintelijaiplugin.history.ChatHistoryService
import com.github.piotrszybicki.independentintelijaiplugin.history.StoredChat
import com.github.piotrszybicki.independentintelijaiplugin.history.StoredRow
import com.github.piotrszybicki.independentintelijaiplugin.mcp.McpService
import com.github.piotrszybicki.independentintelijaiplugin.settings.AICodingAgentCredentials
import com.github.piotrszybicki.independentintelijaiplugin.settings.AICodingAgentSettingsConfigurable
import com.github.piotrszybicki.independentintelijaiplugin.settings.AICodingAgentSettingsState
import com.github.piotrszybicki.independentintelijaiplugin.skills.SkillCatalog
import com.github.piotrszybicki.independentintelijaiplugin.tools.ProjectEnvironment
import com.github.piotrszybicki.independentintelijaiplugin.tools.PsiTargets
import com.github.piotrszybicki.independentintelijaiplugin.tools.RunShellCommandTool
import com.github.piotrszybicki.independentintelijaiplugin.tools.ToolCatalog
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
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

        /**
         * The chat panel for each project. Stored here so that [ChatAboutSelectionAction] can
         * attach code from the editor without walking the Swing tree or the Disposer hierarchy.
         * Cleared when the panel is disposed.
         */
        private val panels = java.util.WeakHashMap<Project, ChatPanel>()

        /**
         * Opens the tool window, starts a fresh chat, and attaches the given code snippet so the
         * user can immediately ask about it. Called from [ChatAboutSelectionAction] when the user
         * clicks the floating toolbar icon on a selection.
         */
        fun openSideChat(project: Project, code: String, displayPath: String, extension: String, lineRange: String) {
            val toolWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                .getToolWindow(TOOL_WINDOW_ID) ?: return
            toolWindow.show {
                val panel = panels[project] ?: return@show
                panel.startSideChat(code, displayPath, extension, lineRange)
            }
        }
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
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
    private class ChatPanel(private val project: Project) : Disposable {

        private val history = mutableListOf<ChatMessage>()

        /**
         * The same conversation as [history], but as the transcript drew it. Kept alongside rather
         * than derived, because the two disagree on purpose -- an attachment is sent in full and
         * shown as a chip -- and it is this list that gets replayed when a saved chat is reopened.
         */
        private val rows = mutableListOf<StoredRow>()

        /** The tool row drawn but not yet settled, and its place in [rows]. Null between tool calls. */
        private var runningTool: ChatTranscript.RunningTool? = null
        private var runningToolIndex = -1

        /**
         * Lazy on purpose. A tool window that was open when the IDE last closed is rebuilt during
         * frame init, and pulling a service out of the container that early -- from the EDT here,
         * and from the pooled thread [restoreLastChat] starts -- is how startup deadlocks and
         * initialization cycles are made. Nothing here needs it until the user does something.
         */
        private val chatHistory by lazy { ChatHistoryService.getInstance(project) }
        private var chatId = ChatHistoryService.newChatId()
        private var chatCreatedAt = System.currentTimeMillis()

        /**
         * The output cap this conversation has earned above the configured one, or 0 while it has
         * earned none. Continuing a cut-off reply doubles the cap, and it would be a poor bargain if
         * that only held until the next message -- a chat producing long answers goes on producing
         * them. Kept here rather than written back to settings: it is this conversation's shape, not
         * the user's preference, and it resets with [resetConversation] like the tool approvals do.
         */
        private var raisedMaxTokens = 0

        /**
         * What this conversation has spent. Scoped to the chat rather than to the IDE, like the
         * approvals and the raised cap either side of it -- the numbers are only meaningful next to
         * the conversation that ran them up, and it is saved with it so switching chats or
         * restarting does not reset the count to zero.
         *
         * Touched from the EDT only: the agent reports usage from its pooled thread and the callback
         * hands it over before adding it in.
         */
        private var usage = SessionUsage()

        private val session = ChangeSessionService.getInstance(project)

        /**
         * Same reasoning as [chatHistory]: connecting to the configured MCP servers starts
         * processes and opens sockets, which is not something to be doing from the EDT while the
         * tool window is being built. The service defers all of that to the first turn.
         */
        private val mcp by lazy { McpService.getInstance(project) }

        /**
         * Every built-in tool, including the ones the settings currently withhold. Building them
         * all costs nothing -- a tool is a name, a schema and a function -- and it means switching
         * one on takes effect on the next message rather than the next time this panel is built.
         */
        private val builtInTools = ToolCatalog.buildAll(project)

        // Held onto so "Always Run in This Chat" can be cleared when the conversation is reset.
        private val shellTool = builtInTools.filterIsInstance<RunShellCommandTool>().firstOrNull()

        private val log = Logger.getInstance(ChatToolWindowFactory::class.java)

        private val agent = AICodingAgent(
            tools = {
                // The MCP tools are wrapped along with the rest. The change session cannot track
                // what a server writes straight to disk -- no more than it can for
                // run_shell_command -- but the wrapper also flushes the model's unsaved edits
                // before every call, which is what lets a server that reads files see them.
                ChangeTrackingTool.wrapAll(ToolCatalog.enabledIn(builtInTools) + mcp.tools(), session)
            },
            environment = { ProjectEnvironment.describe(project) },
            // Reads the skill directories off disk, so this runs on the agent's pooled thread with
            // everything else that must not happen on the EDT.
            skills = { SkillCatalog.describe(project) },
        )

        private val transcript = ChatTranscript(onCancel = { cancelTurn() })

        /** Set for the whole turn; read by the agent between steps and by the pooled thread's catch. */
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
        ) { attachFromEditor() }

        private val statusLabel = JBLabel(" ").apply {
            font = JBFont.small()
            foreground = ChatColors.muted
        }

        /** Running token count for this chat. Empty until the first reply comes back. */
        private val usageLabel = JBLabel().apply {
            font = JBFont.small()
            foreground = ChatColors.muted
        }

        /**
         * The two share the strip under the composer. A row rather than one label, because
         * [statusLabel] is cleared and rewritten by everything that has something to say -- [setBusy]
         * most of all -- and the count has to survive that.
         */
        private val statusRow = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(statusLabel, BorderLayout.CENTER)
            add(usageLabel, BorderLayout.EAST)
        }

        /** One thing riding along with the next message: [body] is sent, [summary] labels the chip. */
        private data class PendingAttachment(val summary: String, val body: String)

        /** Everything attached to the next message, in the order it was attached. */
        private val pendingAttachments = mutableListOf<PendingAttachment>()

        /** One chip per entry in [pendingAttachments]. Rebuilt by [refreshAttachments]. */
        private val attachmentList = JPanel(GridLayout(0, 1, 0, JBUI.scale(3))).apply { isOpaque = false }

        /**
         * Same bargain as [changedFilesScroll]: the chips sit on top of the composer, so a handful
         * of attached files must not push the input off the bottom of the tool window.
         */
        private val attachmentRow = object : JBScrollPane(attachmentList) {
            override fun getPreferredSize(): Dimension {
                val preferred = super.getPreferredSize()
                return Dimension(preferred.width, minOf(preferred.height, JBUI.scale(96)))
            }

            // Long paths must not set a floor on how narrow the tool window can be dragged.
            override fun getMinimumSize(): Dimension = Dimension(0, preferredSize.height)
        }.apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
            isVisible = false
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }

        private val changesLabel = JBLabel().apply {
            font = JBFont.small()
            foreground = ChatColors.foreground
        }
        private val approveButton = JButton("Approve").apply { toolTipText = "Keep all changes the AI made" }
        private val revertButton = JButton("Revert").apply { toolTipText = "Restore all touched files" }
        private val sessionListener = ChangeSessionService.Listener { count -> updateChangesBar(count) }

        /** One clickable row per changed file. Rebuilt whenever the session changes. */
        private val changedFilesList = JPanel(GridLayout(0, 1, 0, JBUI.scale(1))).apply { isOpaque = false }

        /**
         * Caps the list at a few rows: the bar sits on top of the composer, and a refactoring that
         * touched thirty files must not push the input off the bottom of the tool window.
         */
        private val changedFilesScroll = object : JBScrollPane(changedFilesList) {
            override fun getPreferredSize(): Dimension {
                val preferred = super.getPreferredSize()
                return Dimension(preferred.width, minOf(preferred.height, JBUI.scale(132)))
            }

            // Long paths must not set a floor on how narrow the tool window can be dragged; the
            // labels ellipsize instead.
            override fun getMinimumSize(): Dimension = Dimension(0, preferredSize.height)
        }.apply {
            border = JBUI.Borders.emptyTop(JBUI.scale(4))
            isOpaque = false
            viewport.isOpaque = false
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }

        private val changesBar = RoundedPanel(
            BorderLayout(),
            arc = { ChatMetrics.smallArc },
            fill = { ChatColors.card },
            stroke = { ChatColors.separator },
        ).apply {
            isVisible = false
            border = JBUI.Borders.empty(JBUI.scale(5), JBUI.scale(9))
            add(
                JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                    isOpaque = false
                    add(changesLabel, BorderLayout.CENTER)
                    add(
                        JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(5), 0)).apply {
                            isOpaque = false
                            add(approveButton)
                            add(revertButton)
                        },
                        BorderLayout.EAST,
                    )
                },
                BorderLayout.NORTH,
            )
            add(changedFilesScroll, BorderLayout.CENTER)
        }

        /** Rounded input card: text area on top, action buttons tucked into its bottom-right corner. */
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

        /** Everything stacked between the transcript and the input: pending changes, then the attachment chips. */
        private val composerTop = JPanel(BorderLayout(0, JBUI.scale(5))).apply {
            isOpaque = false
            add(changesBar, BorderLayout.NORTH)
            add(attachmentRow, BorderLayout.CENTER)
        }

        private val composer = JPanel(BorderLayout(0, JBUI.scale(5))).apply {
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
            add(transcript.component, BorderLayout.CENTER)
            add(composer, BorderLayout.SOUTH)
        }

        init {
            sendButton.addActionListener { send() }
            approveButton.addActionListener { approveChanges() }
            revertButton.addActionListener { revertChanges() }
            input.addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (e.keyCode != KeyEvent.VK_ENTER) return
                    if (e.isShiftDown) {
                        // Shift+Enter inserts a newline.
                        e.consume()
                        input.replaceSelection("\n")
                    } else {
                        // Plain Enter sends the message.
                        e.consume()
                        send()
                    }
                }
            })
            // The input card draws its own focus ring, so it has to repaint when focus moves.
            input.addFocusListener(object : FocusAdapter() {
                override fun focusGained(e: FocusEvent) = inputCard.repaint()
                override fun focusLost(e: FocusEvent) = inputCard.repaint()
            })

            session.addListener(sessionListener)
            updateChangesBar(session.changedFileCount)
            restoreLastChat()
        }

        override fun dispose() {
            panels.remove(project)
            session.removeListener(sessionListener)
            // Synchronously, unlike every other save: the panel is going away, and a pooled thread
            // started here is not guaranteed to get to run before the project closes.
            saveCurrentChat(active = true, background = false)
        }

        // --- chat history ---------------------------------------------------------------------

        /**
         * Reopens whatever the tool window was last left on, so a restart resumes the conversation.
         *
         * Held back until the project is open rather than run from the constructor: a restored tool
         * window is built during frame init, and reading files -- let alone instantiating a service
         * to do it -- is not something to be doing while the IDE is still starting. `runAfterOpened`
         * fires straight away when the window is opened by hand later on.
         */
        private fun restoreLastChat() {
            StartupManager.getInstance(project).runAfterOpened {
                val chat = chatHistory.activeId()?.let { chatHistory.load(it) } ?: return@runAfterOpened
                ApplicationManager.getApplication().invokeLater({
                    // Reading the files took a moment; if the user has already started talking in
                    // the meantime, their conversation wins.
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
                // The chat on screen was deleted, so there is nothing left to save or come back to.
                onCurrentDeleted = { resetConversation() },
            )
        }

        private fun openChat(id: String) {
            if (id == chatId) return
            if (!sendButton.isEnabled) {
                statusLabel.text = "Wait for the current reply to finish before switching chats."
                return
            }

            // Saved and loaded in one task rather than two, so the outgoing chat is written before
            // the incoming one takes over as the active chat.
            val outgoing = snapshot()
            ApplicationManager.getApplication().executeOnPooledThread {
                if (outgoing != null) chatHistory.save(outgoing, active = false)
                val chat = chatHistory.load(id)
                ApplicationManager.getApplication().invokeLater({
                    if (chat == null) showError("That chat could not be opened.") else applyChat(chat)
                }, project.disposed)
            }
        }

        /** Replaces the conversation on screen -- and the one the model sees -- with [chat]. */
        private fun applyChat(chat: StoredChat) {
            resetConversation(chat.id, chat.createdAt)
            history.addAll(chat.messages)
            rows.addAll(chat.transcript)
            // After the reset, which has just zeroed it.
            setUsage(chat.usage ?: SessionUsage())
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
            // Shell and MCP approvals are given for a conversation, not for the project. So is the
            // raised output cap: a new chat starts back at the configured one.
            shellTool?.forgetApprovals()
            mcp.forgetApprovals()
            raisedMaxTokens = 0
            setUsage(SessionUsage())
            transcript.clear()
            clearAttachments()
        }

        /** Null until the conversation has something worth keeping. */
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
            )
        }

        /**
         * @param active false when the user is leaving this chat, which also stops it being the one
         *   reopened next time
         */
        private fun saveCurrentChat(active: Boolean, background: Boolean = true) {
            // A turn running on the pooled thread appends to history as it goes, and dispose can
            // land mid-turn, so copying the lists is not guaranteed to be safe. A chat that fails
            // to snapshot is one turn behind on disk, which is not worth failing a close over.
            val chat = runCatching { snapshot() }.getOrNull()
            val leaving = chatId

            val write = Runnable {
                when {
                    chat != null -> chatHistory.save(chat, active)
                    // An empty chat has nothing to write, but leaving one still has to stop it
                    // being the chat reopened next time -- New Chat pressed twice, say.
                    !active && chatHistory.activeId() == leaving -> chatHistory.setActiveId(null)
                }
            }
            if (background) ApplicationManager.getApplication().executeOnPooledThread(write) else write.run()
        }

        // --- transcript ------------------------------------------------------------------------

        // Every row goes through one of these: what is drawn and what is stored have to stay in
        // step, or a reopened chat comes back missing pieces.

        private fun showUserMessage(markdown: String) {
            rows += StoredRow(StoredRow.USER, text = markdown)
            transcript.addUserMessage(markdown)
        }

        private fun showAssistantMessage(markdown: String) {
            rows += StoredRow(StoredRow.ASSISTANT, text = markdown)
            transcript.addAssistantMessage(markdown)
        }

        /**
         * Draws a thinking summary as a collapsed row, the same shape a finished tool call gets.
         *
         * Collapsed because it is background rather than the answer, and drawn at all because it is
         * charged for at the output rate: a turn whose token count looks out of proportion to what
         * it said is explained here.
         */
        private fun showThinking(summary: String) {
            val headline = headlineOf(summary)
            rows += StoredRow(StoredRow.THINKING, summary = headline, details = summary)
            transcript.addToolCall("thinking", headline, summary, ChatTranscript.ToolStatus.DONE)
        }

        /** The collapsed row's one line: the first thing the summary says, cut to fit. */
        private fun headlineOf(summary: String): String {
            val line = summary.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
            return if (line.length <= 90) line else line.take(89).trimEnd() + "…"
        }

        /**
         * Draws a compaction pass as a collapsed row, the same shape a finished tool call gets.
         *
         * Shown rather than done silently: it is why the model may go back and re-read a file it
         * already read, and why the input count stops climbing the way it had been. Drawn as a tool
         * row so it is stored, replayed and collapsed like everything else in the turn -- what it
         * did is a detail, that it happened is the line.
         */
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
                    // The two clauses have to read as one sentence in all three combinations: elided
                    // only, summarised only, and both. What differs is why summarising was reached --
                    // because eliding fell short, or because there was nothing to elide in the first
                    // place.
                    if (result.evicted > 0) append(", and that was not enough on its own, so ")
                    else append("and none of it was old tool output that could simply be dropped, so ")
                    append("the model was asked to summarise the ${result.summarizedMessages} oldest ")
                    append("message(s) and the summary was put in their place")
                }
                append(", bringing it to about ${"%,d".format(result.afterTokens)}.\n\n")
                if (result.summarized) {
                    // The one part of compaction that loses something the user cannot get back by
                    // asking the model to read a file again, so it is said outright.
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
            transcript.addToolCall("compact_context", summary, details, ChatTranscript.ToolStatus.DONE)
        }

        private fun showToolCall(name: String, summary: String, details: String, status: ChatTranscript.ToolStatus) {
            rows += StoredRow(StoredRow.TOOL, name = name, summary = summary, details = details, status = stored(status))
            transcript.addToolCall(name, summary, details, status)
        }

        /**
         * Draws a tool call the moment it starts, spinning, and remembers the row so
         * [finishToolCall] can fill in its output. Only one is ever open at a time: the agent runs
         * the model's tool calls one after another.
         */
        private fun startToolCall(name: String, summary: String, details: String) {
            rows += StoredRow(StoredRow.TOOL, name = name, summary = summary, details = details)
            runningToolIndex = rows.lastIndex
            runningTool = transcript.startToolCall(name, summary, details)
        }

        /** Settles the row [startToolCall] opened, on screen and in what gets saved. Does nothing if none is open. */
        private fun finishToolCall(details: String, status: ChatTranscript.ToolStatus) {
            val row = runningTool ?: return
            runningTool = null
            val index = runningToolIndex
            runningToolIndex = -1

            row.finish(details, status)
            rows.getOrNull(index)?.let { rows[index] = it.copy(details = details, status = stored(status)) }
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

        /** Draws a stored row without recording it again -- the replay half of [showUserMessage] and friends. */
        private fun render(row: StoredRow) {
            when (row.kind) {
                StoredRow.USER -> transcript.addUserMessage(row.text)
                StoredRow.ASSISTANT -> transcript.addAssistantMessage(row.text)
                StoredRow.TOOL -> transcript.addToolCall(row.name, row.summary, row.details, toolStatus(row.status))
                StoredRow.THINKING ->
                    transcript.addToolCall("thinking", row.summary, row.details, ChatTranscript.ToolStatus.DONE)
                else -> transcript.addError(row.text)
            }
        }

        private fun updateChangesBar(count: Int) {
            changesBar.isVisible = count > 0
            changesLabel.text = if (count == 1) "1 file changed" else "$count files changed"
            approveButton.isEnabled = count > 0
            revertButton.isEnabled = count > 0

            changedFilesList.removeAll()
            if (count > 0) session.changedFiles().forEach { changedFilesList.add(changedFileRow(it)) }
            // The whole composer moves when the bar appears or grows a row, so revalidating the bar
            // alone leaves the input where it was.
            composer.revalidate()
            composer.repaint()
        }

        /** A row in the pending-changes list: file name, its folder, and a click that opens it. */
        private fun changedFileRow(file: VirtualFile): JComponent {
            var hovered = false
            val row = RoundedPanel(
                BorderLayout(JBUI.scale(6), 0),
                arc = { ChatMetrics.smallArc },
                fill = { if (hovered) ChatColors.cardHover else null },
            ).apply {
                border = JBUI.Borders.empty(JBUI.scale(2), JBUI.scale(4))
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                toolTipText = "Open ${PsiTargets.relativePath(project, file)}"
            }

            // Plain labels, so the click lands on the row rather than on whichever label it hit.
            row.add(
                JBLabel(file.name, file.fileType.icon, SwingConstants.LEFT).apply { font = JBFont.small() },
                BorderLayout.WEST,
            )
            val folder = PsiTargets.relativePath(project, file).substringBeforeLast('/', "")
            if (folder.isNotEmpty()) {
                row.add(
                    JBLabel(folder).apply {
                        font = JBFont.small()
                        foreground = ChatColors.muted
                    },
                    BorderLayout.CENTER,
                )
            }

            row.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = openChangedFile(file)
                override fun mouseEntered(e: MouseEvent) {
                    hovered = true
                    row.repaint()
                }

                override fun mouseExited(e: MouseEvent) {
                    hovered = false
                    row.repaint()
                }
            })
            return row
        }

        private fun openChangedFile(file: VirtualFile) {
            // A file the model deleted, or one moved out from under us, is still listed until the
            // session is settled -- there is nothing to open in that case.
            if (!file.isValid) {
                statusLabel.text = "${file.name} is no longer there."
                return
            }
            FileEditorManager.getInstance(project).openFile(file, true)
        }

        private fun approveChanges() {
            session.approveAll()
        }

        private fun revertChanges() {
            val paths = session.changedPaths()
            if (paths.isEmpty()) return

            val fileList = paths.joinToString("\n") { "  $it" }
            val confirmed = Messages.showYesNoDialog(
                project,
                "Restore ${paths.size} file(s) to their state before this session?\n\n$fileList\n\n" +
                    "Any edits you made to these files yourself will be discarded too.",
                "Revert AI Changes",
                "Revert",
                "Cancel",
                Messages.getWarningIcon(),
            )
            if (confirmed != Messages.YES) return

            val failed = session.revertAll()
            if (failed.isNotEmpty()) {
                Messages.showWarningDialog(
                    project,
                    "These files could not be restored:\n\n" + failed.joinToString("\n") { "  $it" },
                    "Revert Incomplete",
                )
            }
        }

        private fun send() {
            if (!sendButton.isEnabled) return
            val text = input.text.trim()
            val attachments = pendingAttachments.toList()
            if (text.isEmpty() && attachments.isEmpty()) return

            val attached = attachments.joinToString("\n\n") { it.body }
            val messageText = when {
                attachments.isEmpty() -> text
                text.isEmpty() -> attached
                else -> "$attached\n\n$text"
            }

            // The transcript shows a marker per attachment instead of the raw attached code (it's
            // still sent in full as part of messageText below), so the chat window doesn't get
            // cluttered with it. Several become a list, which is what keeps them on separate lines
            // once the markdown is rendered.
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
            clearAttachments()
            setBusy(true)

            val settings = AICodingAgentSettingsState.getInstance().state
            val model = settings.model
            // maxOf rather than the raised value alone, so raising the setting mid-conversation
            // still takes effect -- the raise is a floor this chat has earned, not a replacement.
            val maxTokens = maxOf(settings.maxTokens, raisedMaxTokens)
            val maxIterations = settings.maxIterations
            val contextWindow = settings.contextWindowTokens

            cancelled.set(false)

            // Resolving the endpoint happens here on the pooled thread, alongside the network call.
            turn = ApplicationManager.getApplication().executeOnPooledThread {
                val endpoint = AICodingAgentEndpoint.fromSettings()
                if (endpoint.token.isBlank()) {
                    ApplicationManager.getApplication().invokeLater {
                        rollbackHistoryTo(sizeBeforeTurn)
                        setBusy(false)
                        promptForMissingApiKey()
                    }
                    return@executeOnPooledThread
                }

                try {
                    val reasoning = ReasoningOptions.fromSettings()
                    agent.run(endpoint, model, maxTokens, maxIterations, contextWindow, history, object : AICodingAgent.Listener {
                        override fun onAssistantText(text: String) {
                            if (text.isBlank()) return
                            ApplicationManager.getApplication().invokeLater { showAssistantMessage(text) }
                        }

                        override fun onThinking(summary: String) {
                            ApplicationManager.getApplication().invokeLater { showThinking(summary) }
                        }

                        // Per request, so the count climbs while a long tool loop is still running
                        // rather than jumping once at the end of the turn.
                        override fun onUsage(usage: AICodingAgentUsage) {
                            ApplicationManager.getApplication().invokeLater { addUsage(usage) }
                        }

                        override fun onCompacted(result: HistoryCompaction.Result) {
                            ApplicationManager.getApplication().invokeLater { showCompaction(result) }
                        }

                        override fun onToolStarted(name: String, input: JsonObject, interruptible: Boolean) {
                            ApplicationManager.getApplication().invokeLater {
                                transcript.setCancellable(interruptible)
                                // Details are the arguments alone for now; the output joins them in
                                // onToolCall, which is also what stops the spinner.
                                startToolCall(name, summarizeToolInput(input), toolCallDetails(input, ""))
                            }
                        }

                        override fun onToolCall(
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
                                }
                                val details = toolCallDetails(input, result)
                                // A tool the cancel got to first was never started, so there is no
                                // row waiting for it -- it is drawn here, settled, instead.
                                if (runningTool != null) {
                                    finishToolCall(details, status)
                                } else {
                                    showToolCall(name, summarizeToolInput(input), details, status)
                                }
                            }
                        }

                        // Blocks the agent thread until the user answers: the loop cannot go on
                        // until it knows whether to resume, and the pooled thread is ours to hold.
                        override fun onMaxTokens(limit: Int, suggested: Int): Int? {
                            var next: Int? = null
                            ApplicationManager.getApplication().invokeAndWait {
                                next = askToContinue(limit, suggested)
                            }
                            return next
                        }

                        // Same bargain as onMaxTokens: block here until the user says whether the
                        // agent should keep going.
                        override fun onMaxIterations(used: Int): Boolean {
                            if (cancelled.get()) return false
                            var extend = false
                            ApplicationManager.getApplication().invokeAndWait {
                                extend = askToExtendIterations(used)
                            }
                            return extend
                        }
                    }, isCancelled = cancelled::get, reasoning = reasoning)
                    ApplicationManager.getApplication().invokeLater { endTurn() }
                } catch (e: Throwable) {
                    // Throwable, not Exception: whatever comes out of a turn, the composer has to be
                    // handed back. Anything that is not an Exception used to go straight to the
                    // pooled thread's default handler, leaving the window stuck on "AI is working"
                    // with no way out of it short of restarting the IDE.
                    log.warn("The turn failed", e)
                    // A cancel usually surfaces as an exception first -- an interrupted wait, or a
                    // half-torn-down HTTP call -- and reporting that as a failure would be noise.
                    val message = if (cancelled.get()) null else e.message ?: "The request failed."
                    ApplicationManager.getApplication().invokeLater {
                        if (message != null) {
                            // Drawing the error must not be able to cost the composer either, hence
                            // the guard: endTurn below is the one thing that has to happen.
                            runCatching {
                                // Only the opening request is worth taking back. Nothing has
                                // happened yet at that point, so dropping the message leaves a clean
                                // conversation to send again from -- but once the turn has tool
                                // results in it, discarding them would leave the model blind to work
                                // the transcript still shows and to file changes that really did
                                // happen.
                                if (history.size <= sizeBeforeTurn + 1) rollbackHistoryTo(sizeBeforeTurn)
                                showError(message)
                            }.onFailure { log.warn("Could not report the failure in the transcript", it) }
                        }
                        endTurn()
                    }
                }
            }
        }

        /**
         * Stops the current turn from the transcript's stop button.
         *
         * Two halves, because a turn can be blocked in two different ways: the flag stops the agent
         * loop from starting anything else, and the interrupt unblocks a tool that is already
         * waiting -- await_breakpoint sitting on a latch, or run_shell_command polling the terminal.
         * Neither can stop a shell command that is already running; that is what Ctrl+C in the
         * terminal is for.
         *
         * Frees the composer but deliberately does not save. The agent is still running at this
         * point and may be part-way through an iteration -- the assistant's tool calls appended but
         * their results not yet -- and a conversation written to disk in that state is one the API
         * refuses to accept ever again. Saving is left to the pooled thread, which does it once it
         * has actually unwound and the history is whole.
         */
        private fun cancelTurn() {
            if (sendButton.isEnabled) return
            if (!cancelled.compareAndSet(false, true)) return

            turn?.cancel(true)
            showError("Stopped. The reply is incomplete.")
            setBusy(false)
        }

        private fun setBusy(busy: Boolean) {
            sendButton.isEnabled = !busy
            transcript.setThinking(busy)
            statusLabel.text = " "
        }

        /** Ends a turn on the EDT: the composer goes back to idle and the conversation reaches disk. */
        private fun endTurn() {
            // A turn that died between starting a tool and reporting it would otherwise leave that
            // row spinning for the rest of the conversation -- and save it that way.
            finishToolCall(rows.getOrNull(runningToolIndex)?.details.orEmpty(), ChatTranscript.ToolStatus.FAILED)
            // The model has nothing left to do, so its bubble is closed: the next thing it says
            // belongs to a new turn, not to this one.
            transcript.endAiTurn()
            setBusy(false)
            saveCurrentChat(active = true)
        }

        // --- token usage -------------------------------------------------------------------------

        /** Adds what one request cost to the running total. EDT only. */
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

        /** Compact enough for a narrow tool window: 128443 reads as 128.4k. */
        private fun formatTokens(count: Int): String = when {
            count < 1_000 -> count.toString()
            count < 1_000_000 -> "%.1fk".format(count / 1_000.0)
            else -> "%.2fM".format(count / 1_000_000.0)
        }

        /** Short one-liner shown next to a tool name, e.g. the file it touched. */
        private fun summarizeToolInput(toolInput: JsonObject): String {
            val preferredKeys = listOf("path", "file", "filePath", "name", "newName", "symbol", "query", "text")
            val value = preferredKeys.asSequence()
                .mapNotNull { toolInput.get(it) as? JsonPrimitive }
                .firstOrNull()
                ?: toolInput.entrySet().asSequence().mapNotNull { it.value as? JsonPrimitive }.firstOrNull()
            val summary = value?.asString?.replace('\n', ' ')?.trim().orEmpty()
            return if (summary.length > 70) summary.take(69) + "…" else summary
        }

        private fun toolCallDetails(toolInput: JsonObject, result: String): String = buildString {
            append(prettyJson.toJson(toolInput))
            if (result.isNotBlank()) {
                append("\n\n")
                append(truncate(result))
            }
        }

        private fun truncate(text: String, limit: Int = 4000): String =
            if (text.length <= limit) text else text.take(limit) + "\n… (${text.length - limit} more characters)"

        private fun rollbackHistoryTo(size: Int) {
            while (history.size > size) history.removeAt(history.lastIndex)
        }

        /**
         * A whole file is worth attaching, but not at any size: it is pasted into the request on
         * every turn of the conversation from then on, not just the first. Past this the model is
         * better off reading the parts it needs with read_project_file.
         */
        private val maxAttachmentChars = 60_000

        /**
         * And a ceiling on the pile of them. Each one is cheap to add, but they are concatenated
         * into the message and carried by every turn after it, so the set needs a limit of its own
         * rather than only a per-file one.
         */
        private val maxTotalAttachmentChars = 150_000

        /**
         * Attaches from the focused editor: the selection when there is one, the whole file when
         * there is not. Adds to whatever is already attached, so several files can ride along with
         * one message -- open the next file, press the button again.
         *
         * One button rather than two because the choice is never ambiguous -- having selected code
         * and wanting the whole file instead is not a real intent -- and a narrow tool window has
         * no room for a second one.
         */
        private fun attachFromEditor() {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
            if (editor == null) {
                statusLabel.text = "Open a file in the editor to attach it."
                return
            }

            val selectionModel = editor.selectionModel
            val selectedText = selectionModel.selectedText
            val document = editor.document
            val virtualFile = FileDocumentManager.getInstance().getFile(document)
            val displayPath = displayPathOf(virtualFile)
            val extension = virtualFile?.extension.orEmpty()

            if (selectedText.isNullOrEmpty()) {
                // Read the Document rather than the file on disk: it is what the editor is showing,
                // so unsaved edits -- the user's or an earlier tool call's -- are included.
                val text = document.text
                if (text.isBlank()) {
                    statusLabel.text = "$displayPath is empty."
                    return
                }
                if (text.length > maxAttachmentChars) {
                    statusLabel.text =
                        "$displayPath is too large to attach (${text.length} characters). " +
                            "Select the part you mean, or just ask -- the AI can read it itself."
                    return
                }
                addAttachment(
                    body = fence("Full contents of $displayPath (${document.lineCount} lines)", extension, text),
                    summary = "$displayPath (whole file)",
                )
                return
            }

            val startLine = document.getLineNumber(selectionModel.selectionStart) + 1
            val endLine = document.getLineNumber(selectionModel.selectionEnd) + 1
            val lineRange = if (startLine == endLine) "line $startLine" else "lines $startLine-$endLine"

            addAttachment(
                body = fence("Selected code from $displayPath ($lineRange)", extension, selectedText),
                summary = "$displayPath ($lineRange)",
            )
        }

        /**
         * Saves the current conversation, starts a fresh one, attaches the given code snippet and
         * focuses the input so the user can immediately type a question about it. Called from the
         * floating toolbar action via [ChatToolWindowFactory.openSideChat].
         */
        fun startSideChat(code: String, displayPath: String, extension: String, lineRange: String) {
            startNewChat()
            addAttachment(
                body = fence("Selected code from $displayPath ($lineRange)", extension, code),
                summary = "$displayPath ($lineRange)",
            )
            input.requestFocusInWindow()
        }


        private fun fence(heading: String, extension: String, code: String): String = buildString {
            append(heading).append(":\n")
            append("```").append(extension).append('\n')
            append(code)
            if (!code.endsWith("\n")) append('\n')
            append("```")
        }

        private fun displayPathOf(virtualFile: VirtualFile?): String {
            val basePath = project.basePath
            return when {
                virtualFile == null -> "untitled"
                basePath != null && virtualFile.path.startsWith(basePath) ->
                    virtualFile.path.removePrefix(basePath).trimStart('/', '\\')
                else -> virtualFile.path
            }
        }

        private fun addAttachment(body: String, summary: String) {
            // Attaching the same file or selection twice refreshes it in place rather than sending
            // it twice: the second attach is how a user picks up edits made since the first.
            val existing = pendingAttachments.indexOfFirst { it.summary == summary }
            val othersLength = pendingAttachments
                .filterIndexed { index, _ -> index != existing }
                .sumOf { it.body.length }
            if (othersLength + body.length > maxTotalAttachmentChars) {
                statusLabel.text =
                    "That is more than $maxTotalAttachmentChars characters of attachments. " +
                        "Send what is attached, or remove some of it, first."
                return
            }

            val attachment = PendingAttachment(summary, body)
            if (existing >= 0) pendingAttachments[existing] = attachment else pendingAttachments.add(attachment)
            statusLabel.text = " "
            refreshAttachments()
        }

        private fun removeAttachment(attachment: PendingAttachment) {
            pendingAttachments.remove(attachment)
            refreshAttachments()
        }

        private fun clearAttachments() {
            pendingAttachments.clear()
            refreshAttachments()
        }

        private fun refreshAttachments() {
            attachmentList.removeAll()
            pendingAttachments.forEach { attachmentList.add(attachmentChip(it)) }
            attachmentRow.isVisible = pendingAttachments.isNotEmpty()
            // The whole composer moves when a chip appears or goes, so revalidating the row alone
            // leaves the input where it was.
            composer.revalidate()
            composer.repaint()
        }

        /** A chip above the input, naming one attachment and offering to drop it. */
        private fun attachmentChip(attachment: PendingAttachment): JComponent {
            val chip = RoundedPanel(
                BorderLayout(JBUI.scale(6), 0),
                arc = { ChatMetrics.smallArc },
                fill = { ChatColors.card },
                stroke = { ChatColors.separator },
            ).apply {
                border = JBUI.Borders.empty(JBUI.scale(3), JBUI.scale(7))
                add(
                    JBLabel("📎 ${attachment.summary}").apply {
                        font = JBFont.small()
                        foreground = ChatColors.foreground
                        toolTipText = attachment.summary
                    },
                    BorderLayout.CENTER,
                )
                add(
                    InplaceButton("Remove attachment", AllIcons.Actions.Close) { removeAttachment(attachment) },
                    BorderLayout.EAST,
                )
            }
            // Left-aligned in a row of its own, so the chip hugs its text instead of stretching to
            // the width of the tool window as the grid would otherwise have it.
            return JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                add(chip)
            }
        }

        private fun promptForMissingApiKey() {
            val openSettings = Messages.showYesNoDialog(
                project,
                "Set the ${AICodingAgentCredentials.ENV_VAR} environment variable to your provider's API " +
                    "key, then restart the IDE so it picks the value up.",
                "API Key Missing",
                "Open Settings",
                "Cancel",
                Messages.getWarningIcon(),
            )
            if (openSettings == Messages.YES) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, AICodingAgentSettingsConfigurable::class.java)
            }
        }

        /**
         * Runs on the EDT, called from the agent thread when a reply hit the output token limit.
         * [limit] is the cap that was hit and [suggested] the one to continue at; they are equal once
         * doubling has run into [AICodingAgent.MAX_TOKENS_CEILING], and that is the case that asks
         * for a number rather than a yes. Returns the cap to continue at, or null to stop.
         *
         * Recording the raise happens here rather than at the call site so that [raisedMaxTokens] is
         * only ever touched from the EDT.
         */
        private fun askToContinue(limit: Int, suggested: Int): Int? {
            val next = if (suggested > limit) confirmContinue(limit, suggested) else askForMaxTokens(limit)
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

        /** The ordinary case: offer the doubled cap and take a yes or a no. */
        private fun confirmContinue(limit: Int, suggested: Int): Int? {
            val answer = Messages.showYesNoDialog(
                project,
                "The reply was cut off at the $limit-token output limit.\n\nContinue the reply where " +
                    "it stopped? The rest of this chat gets $suggested tokens a reply, so it is less " +
                    "likely to happen again. This sends another request, so it costs an extra turn.",
                "Response Cut Off",
                "Continue",
                "Stop Here",
                Messages.getQuestionIcon(),
            )
            return if (answer == Messages.YES) suggested else null
        }

        /**
         * Asks outright once the automatic doubling has stopped at
         * [AICodingAgent.MAX_TOKENS_CEILING]. It stops there because replies much longer than that
         * do not finish inside the client's 60-second timeout on a typical connection -- a guess
         * that may be wrong about this one, so from here the number is the user's to pick. Cancel
         * leaves the answer where it stopped.
         */
        private fun askForMaxTokens(limit: Int): Int? {
            val typed = Messages.showInputDialog(
                project,
                "The reply was cut off at the $limit-token output limit, which is as far as this chat " +
                    "raises it on its own.\n\nSet the limit for the rest of this chat and continue, or " +
                    "cancel to keep the answer as it is. Replies much past this size risk timing out " +
                    "before they arrive.",
                "Response Cut Off",
                Messages.getQuestionIcon(),
                limit.toString(),
                object : InputValidator {
                    override fun checkInput(inputString: String): Boolean =
                        inputString.trim().toIntOrNull()?.let { it > 0 } == true

                    override fun canClose(inputString: String): Boolean = checkInput(inputString)
                },
            )
            // Null is Cancel. The validator has already refused anything but a positive integer, so
            // a value that fails to parse here is not a case worth a second message.
            return typed?.trim()?.toIntOrNull()?.takeIf { it > 0 }
        }

        /**
         * Runs on the EDT, called from the agent thread when a turn has used up its tool-call
         * budget while the model is still working. The cap only exists to catch a loop that has run
         * away, and from here the user can see the tool calls it spent -- so let them decide.
         */
        private fun askToExtendIterations(used: Int): Boolean {
            val answer = Messages.showYesNoDialog(
                project,
                "The assistant has made $used rounds of tool calls on this message and is still " +
                    "going.\n\nKeep going? It carries on from where it is, so nothing done so far " +
                    "is lost, and you will be asked again if it runs on. You can also raise Tool " +
                    "calls per message in Settings.",
                "Tool-Call Limit Reached",
                "Keep Going",
                "Stop Here",
                Messages.getQuestionIcon(),
            )
            val extend = answer == Messages.YES
            showError(
                if (extend) "Tool-call limit reached — continuing."
                else "Stopped after $used rounds of tool calls. The reply is incomplete."
            )
            return extend
        }

        private companion object {
            private val prettyJson = GsonBuilder().setPrettyPrinting().create()
        }
    }
}
