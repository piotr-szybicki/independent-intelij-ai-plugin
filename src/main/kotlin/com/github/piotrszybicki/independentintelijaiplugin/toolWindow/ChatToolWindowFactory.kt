package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.InplaceButton
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.github.piotrszybicki.independentintelijaiplugin.anthropic.AnthropicAgent
import com.github.piotrszybicki.independentintelijaiplugin.anthropic.AnthropicEndpoint
import com.github.piotrszybicki.independentintelijaiplugin.anthropic.ChatMessage
import com.github.piotrszybicki.independentintelijaiplugin.changes.ChangeSessionService
import com.github.piotrszybicki.independentintelijaiplugin.changes.ChangeTrackingTool
import com.github.piotrszybicki.independentintelijaiplugin.history.ChatHistoryService
import com.github.piotrszybicki.independentintelijaiplugin.history.StoredChat
import com.github.piotrszybicki.independentintelijaiplugin.history.StoredRow
import com.github.piotrszybicki.independentintelijaiplugin.settings.AnthropicCredentials
import com.github.piotrszybicki.independentintelijaiplugin.settings.AnthropicSettingsConfigurable
import com.github.piotrszybicki.independentintelijaiplugin.settings.AnthropicSettingsState
import com.github.piotrszybicki.independentintelijaiplugin.tools.AddImportTool
import com.github.piotrszybicki.independentintelijaiplugin.tools.ApplyQuickFixTool
import com.github.piotrszybicki.independentintelijaiplugin.tools.AttachLibrarySourcesTool
import com.github.piotrszybicki.independentintelijaiplugin.tools.AwaitBreakpointTool
import com.github.piotrszybicki.independentintelijaiplugin.tools.CreateFileTool
import com.github.piotrszybicki.independentintelijaiplugin.tools.DebuggerActionTool
import com.github.piotrszybicki.independentintelijaiplugin.tools.DeleteFileTool
import com.github.piotrszybicki.independentintelijaiplugin.tools.EditFileLinesTool
import com.github.piotrszybicki.independentintelijaiplugin.tools.EvaluateExpressionTool
import com.github.piotrszybicki.independentintelijaiplugin.tools.FileExistsTool
import com.github.piotrszybicki.independentintelijaiplugin.tools.FindByNameTool
import com.github.piotrszybicki.independentintelijaiplugin.tools.FindImplementationsTool
import com.github.piotrszybicki.independentintelijaiplugin.tools.FindInFilesTool
import com.github.piotrszybicki.independentintelijaiplugin.tools.FindUsagesTool
import com.github.piotrszybicki.independentintelijaiplugin.tools.GetEditorContextTool
import com.github.piotrszybicki.independentintelijaiplugin.tools.GetFileProblemsTool
import com.github.piotrszybicki.independentintelijaiplugin.tools.GetFileStructureTool
import com.github.piotrszybicki.independentintelijaiplugin.tools.GetSymbolInfoTool
import com.github.piotrszybicki.independentintelijaiplugin.tools.InsertMemberTool
import com.github.piotrszybicki.independentintelijaiplugin.tools.ListDirectoryTool
import com.github.piotrszybicki.independentintelijaiplugin.tools.ListOpenFilesTool
import com.github.piotrszybicki.independentintelijaiplugin.tools.MoveFileTool
import com.github.piotrszybicki.independentintelijaiplugin.tools.ProjectEnvironment
import com.github.piotrszybicki.independentintelijaiplugin.tools.ReadLibraryClassTool
import com.github.piotrszybicki.independentintelijaiplugin.tools.ReadProjectFileTool
import com.github.piotrszybicki.independentintelijaiplugin.tools.RenameSymbolTool
import com.github.piotrszybicki.independentintelijaiplugin.tools.RunActionTool
import com.github.piotrszybicki.independentintelijaiplugin.tools.RunAtLocationTool
import com.github.piotrszybicki.independentintelijaiplugin.tools.RunConfigurationTool
import com.github.piotrszybicki.independentintelijaiplugin.tools.RunShellCommandTool
import com.github.piotrszybicki.independentintelijaiplugin.tools.SafeDeleteTool
import com.github.piotrszybicki.independentintelijaiplugin.tools.StartDebugConfigurationTool
import com.github.piotrszybicki.independentintelijaiplugin.tools.ToggleBreakpointTool
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants

class ChatToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val chatPanel = ChatPanel(project)
        val content = ContentFactory.getInstance().createContent(chatPanel.component, null, false)
        Disposer.register(content, chatPanel)
        toolWindow.contentManager.addContent(content)
        toolWindow.setTitleActions(
            listOf(
                DumbAwareAction.create("New Chat", AllIcons.General.Add) { chatPanel.startNewChat() },
                DumbAwareAction.create("Chat History", AllIcons.Vcs.History) { e -> chatPanel.showHistory(e) },
                DumbAwareAction.create("Settings", AllIcons.General.Settings) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, AnthropicSettingsConfigurable::class.java)
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

        private val chatHistory = ChatHistoryService.getInstance(project)
        private var chatId = chatHistory.newChatId()
        private var chatCreatedAt = System.currentTimeMillis()

        private val session = ChangeSessionService.getInstance(project)

        // Held onto so "Always Run in This Chat" can be cleared when the conversation is reset.
        private val shellTool = RunShellCommandTool(project)

        private val agent = AnthropicAgent(
            ChangeTrackingTool.wrapAll(
                listOf(
                    GetEditorContextTool(project),
                    ListOpenFilesTool(project),
                    ListDirectoryTool(project),
                    FileExistsTool(project),
                    ReadProjectFileTool(project),
                    ReadLibraryClassTool(project),
                    AttachLibrarySourcesTool(project),
                    GetFileStructureTool(project),
                    GetFileProblemsTool(project),
                    ApplyQuickFixTool(project),
                    EditFileLinesTool(project),
                    CreateFileTool(project),
                    MoveFileTool(project),
                    DeleteFileTool(project),
                    FindInFilesTool(project),
                    FindByNameTool(project),
                    FindUsagesTool(project),
                    FindImplementationsTool(project),
                    GetSymbolInfoTool(project),
                    RenameSymbolTool(project),
                    SafeDeleteTool(project),
                    AddImportTool(project),
                    InsertMemberTool(project),
                    ToggleBreakpointTool(project),
                    RunConfigurationTool(project),
                    RunAtLocationTool(project),
                    StartDebugConfigurationTool(project),
                    AwaitBreakpointTool(project),
                    DebuggerActionTool(project),
                    EvaluateExpressionTool(project),
                    RunActionTool(project),
                    shellTool,
                ),
                session,
            ),
            environment = { ProjectEnvironment.describe(project) },
        )

        private val transcript = ChatTranscript(onCancel = { cancelTurn() })

        /** Set for the whole turn; read by the agent between steps and by the pooled thread's catch. */
        private val cancelled = AtomicBoolean(false)
        private var turn: Future<*>? = null

        private val input = JBTextArea(3, 40).apply {
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty()
            background = UIUtil.getTextFieldBackground()
            emptyText.text = "Ask AI about this project…"
        }

        private val sendButton = JButton("Send").apply {
            toolTipText = "Send message (Enter; Ctrl+Enter or Shift+Enter for a new line)"
        }

        private val attachButton = InplaceButton(
            "Attach the editor selection, or the whole file when nothing is selected",
            AllIcons.Actions.AddFile,
        ) { attachFromEditor() }

        private val statusLabel = JBLabel(" ").apply {
            font = JBFont.small()
            foreground = ChatColors.muted
        }

        private var pendingAttachment: String? = null
        private var pendingAttachmentSummary: String? = null

        private val attachmentLabel = JBLabel().apply {
            font = JBFont.small()
            foreground = ChatColors.foreground
        }

        /** Chip above the input showing what code is riding along with the next message. */
        private val attachmentChip = RoundedPanel(
            BorderLayout(JBUI.scale(6), 0),
            arc = { ChatMetrics.smallArc },
            fill = { ChatColors.card },
            stroke = { ChatColors.separator },
        ).apply {
            border = JBUI.Borders.empty(JBUI.scale(3), JBUI.scale(7))
            add(attachmentLabel, BorderLayout.CENTER)
            add(
                InplaceButton("Remove attachment", AllIcons.Actions.Close) { clearAttachment() },
                BorderLayout.EAST,
            )
        }

        private val attachmentRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            isVisible = false
            add(attachmentChip)
        }

        private val changesLabel = JBLabel().apply {
            font = JBFont.small()
            foreground = ChatColors.foreground
        }
        private val approveButton = JButton("Approve").apply { toolTipText = "Keep all changes the AI made" }
        private val revertButton = JButton("Revert").apply { toolTipText = "Restore all touched files" }
        private val sessionListener = ChangeSessionService.Listener { count -> updateChangesBar(count) }

        private val changesBar = JPanel(BorderLayout()).apply {
            isOpaque = false
            isVisible = false
            border = JBUI.Borders.empty(10, 12, 0, 12)
            add(
                RoundedPanel(
                    BorderLayout(JBUI.scale(8), 0),
                    arc = { ChatMetrics.smallArc },
                    fill = { ChatColors.card },
                    stroke = { ChatColors.separator },
                ).apply {
                    border = JBUI.Borders.empty(JBUI.scale(5), JBUI.scale(9))
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
                BorderLayout.CENTER,
            )
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

        private val composer = JPanel(BorderLayout(0, JBUI.scale(5))).apply {
            isOpaque = false
            border = BorderFactory.createCompoundBorder(
                JBUI.Borders.customLineTop(ChatColors.separator),
                JBUI.Borders.empty(9, 12, 10, 12),
            )
            add(attachmentRow, BorderLayout.NORTH)
            add(inputCard, BorderLayout.CENTER)
            add(statusLabel, BorderLayout.SOUTH)
        }

        val component: JComponent = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = ChatColors.background
            add(changesBar, BorderLayout.NORTH)
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
                    if (e.isControlDown) {
                        // Ctrl+Enter is not bound to a newline by default, so insert one by hand.
                        e.consume()
                        input.replaceSelection("\n")
                    } else if (!e.isShiftDown) {
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
            session.removeListener(sessionListener)
            // Synchronously, unlike every other save: the panel is going away, and a pooled thread
            // started here is not guaranteed to get to run before the project closes.
            saveCurrentChat(active = true, background = false)
        }

        // --- chat history ---------------------------------------------------------------------

        /** Reopens whatever the tool window was last left on, so a restart resumes the conversation. */
        private fun restoreLastChat() {
            ApplicationManager.getApplication().executeOnPooledThread {
                val chat = chatHistory.activeId()?.let { chatHistory.load(it) } ?: return@executeOnPooledThread
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
            chat.transcript.forEach { render(it) }
            ApplicationManager.getApplication().executeOnPooledThread { chatHistory.setActiveId(chat.id) }
        }

        private fun resetConversation(
            id: String = chatHistory.newChatId(),
            createdAt: Long = System.currentTimeMillis(),
        ) {
            chatId = id
            chatCreatedAt = createdAt
            history.clear()
            rows.clear()
            // Shell approvals are given for a conversation, not for the project.
            shellTool.forgetApprovals()
            transcript.clear()
            clearAttachment()
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

        private fun showToolCall(name: String, summary: String, details: String) {
            rows += StoredRow(StoredRow.TOOL, name = name, summary = summary, details = details)
            transcript.addToolCall(name, summary, details)
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
                StoredRow.TOOL -> transcript.addToolCall(row.name, row.summary, row.details)
                else -> transcript.addError(row.text)
            }
        }

        private fun updateChangesBar(count: Int) {
            changesBar.isVisible = count > 0
            changesLabel.text = if (count == 1) "1 file changed in this session" else "$count files changed in this session"
            approveButton.isEnabled = count > 0
            revertButton.isEnabled = count > 0
            changesBar.revalidate()
            changesBar.repaint()
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
            val attachment = pendingAttachment
            val attachmentSummary = pendingAttachmentSummary
            if (text.isEmpty() && attachment == null) return

            val messageText = when {
                attachment == null -> text
                text.isEmpty() -> attachment
                else -> "$attachment\n\n$text"
            }

            // The transcript shows a marker instead of the raw attached code (it's still sent in
            // full as part of messageText below), so the chat window doesn't get cluttered with it.
            val displayText = when {
                attachment == null -> text
                text.isEmpty() -> "📎 `$attachmentSummary`"
                else -> "📎 `$attachmentSummary`\n\n$text"
            }

            val sizeBeforeTurn = history.size
            history.add(ChatMessage.text("user", messageText))
            showUserMessage(displayText)
            input.text = ""
            clearAttachment()
            setBusy(true)

            val settings = AnthropicSettingsState.getInstance().state
            val model = settings.model
            val maxTokens = settings.maxTokens

            cancelled.set(false)

            // Resolving the endpoint happens here on the pooled thread, alongside the network call.
            turn = ApplicationManager.getApplication().executeOnPooledThread {
                val endpoint = AnthropicEndpoint.fromSettings()
                if (endpoint.token.isBlank()) {
                    ApplicationManager.getApplication().invokeLater {
                        rollbackHistoryTo(sizeBeforeTurn)
                        setBusy(false)
                        promptForMissingApiKey()
                    }
                    return@executeOnPooledThread
                }

                try {
                    agent.run(endpoint, model, maxTokens, history, object : AnthropicAgent.Listener {
                        override fun onAssistantText(text: String) {
                            if (text.isBlank()) return
                            ApplicationManager.getApplication().invokeLater { showAssistantMessage(text) }
                        }

                        override fun onToolStarted(name: String, interruptible: Boolean) {
                            ApplicationManager.getApplication().invokeLater { transcript.setCancellable(interruptible) }
                        }

                        override fun onToolCall(name: String, toolInput: JsonObject, result: String) {
                            ApplicationManager.getApplication().invokeLater {
                                transcript.setCancellable(true)
                                showToolCall(name, summarizeToolInput(toolInput), toolCallDetails(toolInput, result))
                            }
                        }

                        // Blocks the agent thread until the user answers: the loop cannot go on
                        // until it knows whether to resume, and the pooled thread is ours to hold.
                        override fun onMaxTokens(): Boolean {
                            var resume = false
                            ApplicationManager.getApplication().invokeAndWait {
                                resume = askToContinue()
                            }
                            return resume
                        }
                    }, isCancelled = cancelled::get)
                    ApplicationManager.getApplication().invokeLater { endTurn() }
                } catch (e: Exception) {
                    // A cancel usually surfaces as an exception first -- an interrupted wait, or a
                    // half-torn-down HTTP call -- and reporting that as a failure would be noise.
                    if (cancelled.get()) {
                        ApplicationManager.getApplication().invokeLater { endTurn() }
                        return@executeOnPooledThread
                    }
                    ApplicationManager.getApplication().invokeLater {
                        rollbackHistoryTo(sizeBeforeTurn)
                        showError(e.message ?: "The request failed.")
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
         */
        private fun cancelTurn() {
            if (sendButton.isEnabled) return
            if (!cancelled.compareAndSet(false, true)) return

            turn?.cancel(true)
            showError("Stopped. The reply is incomplete.")
            endTurn()
        }

        private fun setBusy(busy: Boolean) {
            sendButton.isEnabled = !busy
            transcript.setThinking(busy)
            statusLabel.text = " "
        }

        /** Ends a turn on the EDT: the composer goes back to idle and the conversation reaches disk. */
        private fun endTurn() {
            setBusy(false)
            saveCurrentChat(active = true)
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
         * Attaches from the focused editor: the selection when there is one, the whole file when
         * there is not.
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
                setAttachment(
                    body = fence("Full contents of $displayPath (${document.lineCount} lines)", extension, text),
                    summary = "$displayPath (whole file)",
                )
                return
            }

            val startLine = document.getLineNumber(selectionModel.selectionStart) + 1
            val endLine = document.getLineNumber(selectionModel.selectionEnd) + 1
            val lineRange = if (startLine == endLine) "line $startLine" else "lines $startLine-$endLine"

            setAttachment(
                body = fence("Selected code from $displayPath ($lineRange)", extension, selectedText),
                summary = "$displayPath ($lineRange)",
            )
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

        private fun setAttachment(body: String, summary: String) {
            pendingAttachment = body
            pendingAttachmentSummary = summary
            attachmentLabel.text = "📎 $summary"
            attachmentLabel.toolTipText = summary
            attachmentRow.isVisible = true
            statusLabel.text = " "
            composer.revalidate()
            composer.repaint()
        }

        private fun clearAttachment() {
            pendingAttachment = null
            pendingAttachmentSummary = null
            attachmentLabel.text = ""
            attachmentLabel.toolTipText = null
            attachmentRow.isVisible = false
            composer.revalidate()
            composer.repaint()
        }

        private fun promptForMissingApiKey() {
            val openSettings = Messages.showYesNoDialog(
                project,
                "Set the ${AnthropicCredentials.ENV_VAR} environment variable to your Anthropic API " +
                    "key, then restart the IDE so it picks the value up.",
                "API Key Missing",
                "Open Settings",
                "Cancel",
                Messages.getWarningIcon(),
            )
            if (openSettings == Messages.YES) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, AnthropicSettingsConfigurable::class.java)
            }
        }

        /** Runs on the EDT, called from the agent thread when a reply hit the output token limit. */
        private fun askToContinue(): Boolean {
            val answer = Messages.showYesNoDialog(
                project,
                "The reply was cut off at the ${AnthropicSettingsState.getInstance().state.maxTokens}-token " +
                    "output limit.\n\nContinue the reply where it stopped? This sends another request, so it " +
                    "costs an extra turn. You can also raise Max Tokens in Settings.",
                "Response Cut Off",
                "Continue",
                "Stop Here",
                Messages.getQuestionIcon(),
            )
            val resume = answer == Messages.YES
            showError(
                if (resume) "Response hit the max_tokens limit — continuing."
                else "Response hit the max_tokens limit and is incomplete."
            )
            return resume
        }

        private companion object {
            private val prettyJson = GsonBuilder().setPrettyPrinting().create()
        }
    }
}
