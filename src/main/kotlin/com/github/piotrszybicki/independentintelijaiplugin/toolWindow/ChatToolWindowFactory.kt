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
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
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
import com.github.piotrszybicki.independentintelijaiplugin.logging.ModelPricing
import com.github.piotrszybicki.independentintelijaiplugin.mcp.McpService
import com.github.piotrszybicki.independentintelijaiplugin.settings.AICodingAgentSettingsConfigurable
import com.github.piotrszybicki.independentintelijaiplugin.settings.AICodingAgentSettingsState
import com.github.piotrszybicki.independentintelijaiplugin.settings.AgentConfiguration
import com.github.piotrszybicki.independentintelijaiplugin.settings.AgentConfigurations
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
import java.math.BigDecimal
import java.nio.file.Path
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.BorderFactory
import javax.swing.DefaultComboBoxModel
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

        /**
         * Re-reads the configuration file into every open chat panel's provider bar.
         *
         * Called from the settings page when it applies, because the two show the same two settings
         * and the page is where a provider can be added as well as picked -- a bar still offering
         * yesterday's list is the same staleness as a bar that never refreshed at all.
         */
        fun refreshProviderBars() = panels.values.forEach { it.refreshProviderBar() }

        /**
         * Tells a panel that was rebuilt during frame init that the project is open, so it can read
         * the chat it was last left on. Called from [ChatRestoreActivity]; no-op when the tool window
         * has not been opened yet, because a panel built later restores itself -- see
         * `ChatPanel.restoreLastChat`.
         */
        internal fun projectOpened(project: Project) {
            // On the EDT, where [panels] and [openedProjects] are both written and read: that is
            // what keeps the two restore paths from racing, given this runs on whatever thread the
            // startup activity was dispatched to.
            ApplicationManager.getApplication().invokeLater({
                openedProjects += project
                panels[project]?.restoreLastChatNow()
            }, project.disposed)
        }

        /**
         * The projects [projectOpened] has been called for. A panel built after its project is open
         * finds it here and restores itself; one built before does not, and waits to be called.
         *
         * Weak keys, like [panels]: an entry outlives nothing.
         */
        private val openedProjects: MutableSet<Project> =
            java.util.Collections.newSetFromMap(java.util.WeakHashMap())

        /** Whether [projectOpened] has already run for [project]. EDT only. */
        internal fun isOpened(project: Project) = project in openedProjects
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // The one state that is refused rather than worked around: a configuration file named by the
        // environment that is not there. No panel is built, so there is nothing to send from and no
        // chat can start on a provider the user did not choose -- see
        // AgentConfigurations.unavailableReason.
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

    /**
     * What the tool window holds instead of a chat when the plugin will not run: the reason, and the
     * two ways out of it.
     *
     * A panel rather than a hidden tool window, because a tool window that is simply not there is
     * indistinguishable from a plugin that failed to install -- and the path in the message is the
     * whole answer.
     */
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
         * The cards of calls whose output the limit withheld, by `tool_use` id, while their Approve
         * buttons are still live.
         *
         * A map because a single response can ask for six tools and have more than one of them
         * overrun: each gets its own offer, on its own card, and the id is what says which result an
         * approval rewrites.
         *
         * Deliberately not part of [rows], so the offers are neither saved with the chat nor
         * replayed when one is reopened. An offer is about a turn that has just stopped; a day
         * later, in a chat loaded from history, approving would splice output into a conversation
         * that has long since moved on. The withheld notes stay in the conversation either way, so
         * nothing is lost by an offer expiring -- the chat simply carries on from them.
         */
        private val withheldCards = mutableMapOf<String, ChatTranscript.RunningTool>()

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
         * Set by whichever half of [restoreLastChat] gets there first, so the last chat is read once
         * even when both do. Declared up here rather than beside the function because the init block
         * calls [restoreLastChat], and a property declared after that call is still null when it runs.
         */
        private val restoreStarted = AtomicBoolean(false)

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

        // One per conversation, reset with it: see [ContextMeter].
        private val contextMeter = ContextMeter()

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

        private val transcript = ChatTranscript(
            project,
            onCancel = { cancelTurn() },
            onContinue = { continueTurn() },
        )

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

        /**
         * True while [refreshProviderBar] is refilling the two combos below, so the listeners that
         * save a choice do not fire for a choice the user did not make.
         */
        private var refillingProviderBar = false

        /**
         * The entries in the configuration file as [refreshProviderBar] last read them, so a name
         * picked below can be turned back into the configuration it came from.
         */
        private var providers: List<AgentConfiguration> = emptyList()

        /**
         * Which provider and model *this conversation* is on, by name. Empty means "whatever the
         * file's first entry is" and "whatever that entry's default model is", which is what a chat
         * started before either was recorded comes back as.
         *
         * Per conversation rather than per IDE, because it is a property of the chat: a reply is
         * answered by one model, and a transcript that was half Sonnet and half Opus with no record
         * of where the change happened is a transcript that cannot be read back honestly. They are
         * saved with the chat and restored with it -- see [snapshot] and [applyChat] -- while the
         * application-wide setting behind them is only the default a *new* chat starts on.
         *
         * Changing either still applies from the next message, not retroactively: the history is
         * provider-agnostic, so the conversation so far carries over to whatever is picked.
         */
        private var chatConfiguration: String = ""
        private var chatModel: String = ""

        /**
         * Which entry of the configuration file this chat sends to.
         *
         * Holds names rather than configurations, which is what lets it go without a cell renderer:
         * both of the ones that would draw an object are deprecated, and a combo of the strings it
         * was going to display anyway needs neither.
         */
        private val providerCombo = ComboBox(DefaultComboBoxModel<String>()).apply {
            font = JBFont.small()
            toolTipText = "Which provider in ${AgentConfiguration.FILE_NAME} to send to"
            addActionListener { if (!refillingProviderBar) providerChosen() }
        }

        /**
         * Which of that entry's `models` to ask for. Its own control rather than one list of
         * provider-and-model pairs: the token, the URL and the protocol do not change when only the
         * model does, and a provider offering six models would otherwise be six entries in the file.
         */
        private val modelCombo = ComboBox(DefaultComboBoxModel<String>()).apply {
            font = JBFont.small()
            toolTipText = "Which model to ask this provider for"
            addActionListener { if (!refillingProviderBar) modelChosen() }
        }

        /**
         * Sits above the transcript, because it is the one setting that is worth changing mid-chat
         * and reading without changing -- what a conversation is costing depends on it, and the
         * settings dialog is a poor place to keep checking.
         *
         * Takes effect on the next message: the history is provider-agnostic, so switching partway
         * hands the conversation so far to the model picked here.
         */
        private val providerBar = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            isOpaque = false
            border = BorderFactory.createCompoundBorder(
                JBUI.Borders.customLineBottom(ChatColors.separator),
                JBUI.Borders.empty(4, 8),
            )
            add(
                JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                    isOpaque = false
                    add(providerCombo)
                    add(modelCombo)
                },
                BorderLayout.CENTER,
            )
            add(
                InplaceButton("Re-read ${AgentConfiguration.FILE_NAME}", AllIcons.Actions.Refresh) {
                    refreshProviderBar()
                },
                BorderLayout.EAST,
            )
        }

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
         * How full the context window is, as against [usageLabel]'s running total of what the
         * conversation has cost. Two different questions, so two different labels: one climbs for
         * as long as the chat is open, the other can go down.
         */
        private val contextLabel = JBLabel().apply {
            font = JBFont.small()
            foreground = ChatColors.muted
        }

        // Right-aligned and laid out by the flow rather than pinned east, so that the two read as
        // one group and neither moves when the other is empty.
        private val meterRow = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(10), 0)).apply {
            isOpaque = false
            add(contextLabel)
            add(usageLabel)
        }

        /**
         * The two share the strip under the composer. A row rather than one label, because
         * [statusLabel] is cleared and rewritten by everything that has something to say -- [setBusy]
         * most of all -- and the count has to survive that.
         */
        private val statusRow = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(statusLabel, BorderLayout.CENTER)
            add(meterRow, BorderLayout.EAST)
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
            add(providerBar, BorderLayout.NORTH)
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
            seedSelectionFromDefault()
            restoreLastChat()
        }

        /**
         * Starts this conversation on the application-wide default -- the last provider and model
         * chosen in any chat, see [rememberAsDefault] -- and shows it.
         *
         * Both the first chat of a session and every new one after it come through here. A chat
         * being *reopened* overwrites the result in [applyChat] with what it was saved on.
         */
        private fun seedSelectionFromDefault() {
            val settings = AICodingAgentSettingsState.getInstance().state
            chatConfiguration = settings.activeConfiguration
            chatModel = settings.activeModel
            refreshProviderBar()
        }

        /**
         * Refills both combos from the file and from what was last chosen.
         *
         * Called at construction, after either choice, and from the refresh button beside them. Not
         * on a file watcher: the file is edited in the editor a few centimetres away, and a chat
         * whose provider changed underneath it while a message was being typed would be worse than
         * one that waits to be asked.
         */
        fun refreshProviderBar() {
            refillingProviderBar = true
            try {
                providers = AgentConfigurations.getInstance(project).load().configurations
                // This conversation's own selection, resolved the same way the turn will resolve it,
                // so the bar cannot show one thing while the next message goes to another.
                val active = AgentConfigurations.select(providers, chatConfiguration)
                    ?: AgentConfiguration.fallback()

                providerCombo.model = DefaultComboBoxModel(providers.map { it.name }.toTypedArray())
                providerCombo.isEnabled = providers.isNotEmpty()
                providerCombo.selectedItem = active.name

                modelCombo.model = DefaultComboBoxModel(active.models.toTypedArray())
                // Enabled whenever it has anything in it, even a list of one. Greying it out for a
                // single model says "nothing to choose here", which is indistinguishable from "this
                // control is broken" when the list is short because the file was read stale.
                modelCombo.isEnabled = active.models.isNotEmpty()
                modelCombo.selectedItem = active.withModel(chatModel).model
            } finally {
                refillingProviderBar = false
            }
        }

        /**
         * Saves the provider and gives up the chosen model with it, because the model list belongs
         * to the entry: keeping a name the new provider has never heard of would either send it or
         * silently ignore it, and neither reads as what the dropdown just said.
         */
        private fun providerChosen() {
            val chosen = providers.firstOrNull { it.name == providerCombo.selectedItem } ?: return
            if (chosen.name == chatConfiguration) return
            chatConfiguration = chosen.name
            // Given up with the provider, because the model list belongs to the entry: keeping a
            // name the new provider has never heard of would either send it or be silently ignored,
            // and neither reads as what the dropdown just said.
            chatModel = ""
            rememberAsDefault()
            refreshProviderBar()
            statusLabel.text = "Sending to ${chosen.name} (${chosen.model}) from the next message."
        }

        private fun modelChosen() {
            val chosen = modelCombo.selectedItem as? String ?: return
            if (chosen == chatModel) return
            chatModel = chosen
            rememberAsDefault()
            statusLabel.text = "Asking for $chosen from the next message."
        }

        /**
         * Carries this chat's choice into the application-wide default, so the *next* new chat picks
         * up where this one left off.
         *
         * Per-chat would otherwise mean every new conversation snapping back to whatever the
         * settings page last said, which is not what switching to a better model for the afternoon
         * is meant to do. A reopened chat still restores its own -- that is the part the default
         * must not overrule.
         */
        private fun rememberAsDefault() {
            val settings = AICodingAgentSettingsState.getInstance().state
            settings.activeConfiguration = chatConfiguration
            settings.activeModel = chatModel
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
         * to do it -- is not something to be doing while the IDE is still starting.
         *
         * In two halves because `StartupManager.runAfterOpened`, which used to be both, is
         * @ApiStatus.Internal and so fails the plugin verifier. A tool window opened by hand after
         * startup takes the first half and restores straight away; one rebuilt during frame init
         * takes the second, where [ChatRestoreActivity] -- an ordinary `postStartupActivity` -- calls
         * [restoreLastChatNow] once the project is open.
         */
        private fun restoreLastChat() {
            if (isOpened(project)) restoreLastChatNow()
        }

        /** @see restoreLastChat */
        fun restoreLastChatNow() {
            if (!restoreStarted.compareAndSet(false, true)) return
            // Off the EDT: this reads the chat off disk, and one caller is the panel's constructor.
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
            // After the reset, which has just seeded these from the default. A chat saved before
            // they were recorded has null for both and keeps the default, which is the closest
            // thing to the truth still available: what it actually ran on was never written down.
            chat.configurationName?.let { chatConfiguration = it }
            chat.model?.let { chatModel = it }
            refreshProviderBar()
            // Reopening is not choosing, so the default is left alone: coming back to an old chat on
            // a cheap model must not quietly make that the model every new chat starts on.
            setUsage(chat.usage ?: SessionUsage())
            // No overhead passed, and none needed: an anchor is a figure the provider gave for a
            // whole request, so the system prompt and the tool schemas are already inside it. When
            // there is no anchor there is nothing worth showing either -- see [StoredChat.context].
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
            // The offers belong to the conversation that stopped, and there is about to be a
            // different one. The files they point at are left where they are: they are the user's
            // now. Nothing is settled on screen, because the transcript is about to be cleared.
            withheldCards.clear()
            // Shell and MCP approvals are given for a conversation, not for the project. So is the
            // raised output cap: a new chat starts back at the configured one.
            shellTool?.forgetApprovals()
            mcp.forgetApprovals()
            raisedMaxTokens = 0
            seedSelectionFromDefault()
            setUsage(SessionUsage())
            // A different conversation, so both the anchor and what was learned about the
            // tokenizer go with the old one -- and the label is cleared rather than left showing
            // the last chat's figure until the first response of this one.
            contextMeter.reset()
            setContext(0, 0)
            beginTurnCost()
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
                context = contextMeter.snapshot(),
                configurationName = chatConfiguration,
                model = chatModel,
            )
        }

        /**
         * This conversation's window, resolved the way the next turn will resolve it.
         *
         * Read from the configuration each time rather than held, for the same reason the turn
         * reads it: the file is the record, and an entry edited while the chat sat open must not
         * leave the meter measuring against a window that is no longer there.
         */
        private fun contextWindowTokens(): Int =
            AgentConfigurations.getInstance(project).resolve(chatConfiguration, chatModel).contextWindowTokens

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
            // No request id: a thinking summary borrows the tool card's shape but is not a tool
            // call, so it is drawn on its own rather than inside the round's box.
            transcript.addToolCall("", "thinking", headline, summary, ChatTranscript.ToolStatus.DONE)
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
            // Drawn like a tool call and stored like one, but it is the loop's own work rather than
            // anything a response asked for, so it has no request to be boxed with.
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

        /**
         * Draws a tool call the moment it starts, spinning, and remembers the row so
         * [finishToolCall] can fill in its output. Only one is ever open at a time: the agent runs
         * the model's tool calls one after another, even when the response asked for several.
         */
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

        /**
         * Settles the row [startToolCall] opened, on screen and in what gets saved, and hands it
         * back so an offer can be attached to it later. Null when no row was open.
         */
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

        /** Draws a stored row without recording it again -- the replay half of [showUserMessage] and friends. */
        private fun render(row: StoredRow) {
            when (row.kind) {
                StoredRow.USER -> transcript.addUserMessage(row.text)
                StoredRow.ASSISTANT -> transcript.addAssistantMessage(row.text)
                // orEmpty because a chat saved before the id was recorded has none, and those rows
                // replay ungrouped -- which is how they were drawn when they were written.
                StoredRow.TOOL ->
                    transcript.addToolCall(
                        row.requestId.orEmpty(), row.name, row.summary, row.details, toolStatus(row.status),
                    )
                StoredRow.THINKING ->
                    transcript.addToolCall("", "thinking", row.summary, row.details, ChatTranscript.ToolStatus.DONE)
                // Draws nothing of its own: it puts the figure back under the bubble the rows before
                // it just built. A number that will not parse is skipped rather than shown as zero.
                StoredRow.COST -> row.text.toBigDecimalOrNull()?.let {
                    transcript.setTurnCost(costLabel(it), costTooltip(row.name, row.summary.toIntOrNull() ?: 1))
                }
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

            startTurn(sizeBeforeTurn)
        }

        /**
         * Sends the conversation as it stands and drives the turn it starts.
         *
         * Split out from [send] because a turn does not always begin with something the user typed:
         * [continueWithEditedOutput] restarts one from a tool result the user rewrote, with nothing
         * added to the conversation at all. Everything from here on is the same either way.
         *
         * [sizeBeforeTurn] is how far back the failure path may roll the history: the point it was at
         * before this turn touched it.
         */
        private fun startTurn(sizeBeforeTurn: Int) {
            setBusy(true)
            // The request is going out again, so the failure the last one ended in is over: the red
            // frame and its Retry button come off before anything else happens.
            transcript.clearRequestFailure()
            // Before anything goes out: from here the withheld notes are on their way to the
            // provider, and an approval after that would rewrite a block it has already been sent.
            settleApprovals()

            // Read once, here, rather than separately for each thing it decides: model, endpoint,
            // limits and reasoning all come from the same entry, and a file saved between two reads
            // would send one provider's model to another provider's URL.
            //
            // This conversation's own choice, not the application-wide default -- the two agree
            // until a second chat picks something else.
            val configuration = AgentConfigurations.getInstance(project).resolve(chatConfiguration, chatModel)
            val model = configuration.model
            // maxOf rather than the raised value alone, so raising the setting mid-conversation
            // still takes effect -- the raise is a floor this chat has earned, not a replacement.
            val maxTokens = maxOf(configuration.maxTokens, raisedMaxTokens)
            val maxIterations = AICodingAgentSettingsState.getInstance().state.maxIterations
            val maxToolOutputTokens = AICodingAgentSettingsState.getInstance().state.maxToolOutputTokens
            val contextWindow = configuration.contextWindowTokens
            // Read here rather than on the pooled thread, for the same reason as the settings above:
            // it is what the turn's log files are filed under, and a chat switched part-way through
            // would scatter one turn's requests across two directories.
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
                        // Retry as well as the dialog: the token is fixed in settings or in the
                        // environment, and this is what sends the message once it has been -- without
                        // it the request the rollback just took out of the conversation would have to
                        // be typed again.
                        offerRetry(sizeBeforeTurn, dropped)
                        promptForMissingApiKey(configuration)
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

                        // Per request, so the count and the cost climb while a long tool loop is
                        // still running rather than jumping once at the end of the turn.
                        override fun onUsage(usage: AICodingAgentUsage) {
                            ApplicationManager.getApplication().invokeLater {
                                addUsage(usage)
                                // The model this turn was actually sent to, read before it started:
                                // switching the dropdown mid-turn must not reprice what is already
                                // in flight at the new model's rates.
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
                                // Details are the arguments alone for now; the output joins them in
                                // onToolCall, which is also what stops the spinner.
                                startToolCall(
                                    call.requestId, name, summarizeToolInput(input), toolCallDetails(input, ""),
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
                                    // The call itself was fine and its output is right here to be
                                    // read -- what failed is that it was too big to send, which the
                                    // error row that follows says in as many words.
                                    AICodingAgent.ToolOutcome.TOO_LARGE -> ChatTranscript.ToolStatus.FAILED
                                }
                                val details = toolCallDetails(input, result)
                                // A tool the cancel got to first was never started, so there is no
                                // row waiting for it -- it is drawn here, settled, instead.
                                val card = if (runningTool != null) {
                                    finishToolCall(details, status)
                                } else {
                                    showToolCall(call.requestId, name, summarizeToolInput(input), details, status)
                                }
                                // Kept only for the calls that are about to be offered for approval.
                                // onToolOutputTooLarge follows once the whole round has run, and by
                                // then this is the only way back to the card that drew this call.
                                if (outcome == AICodingAgent.ToolOutcome.TOO_LARGE && card != null) {
                                    withheldCards[call.toolUseId] = card
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

                        // Once per oversized call, after the whole round has run -- so a response
                        // that asked for six tools and overran on two of them offers two approvals.
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
                                val dropped = if (history.size <= sizeBeforeTurn + 1) {
                                    // Held rather than lost: this is what Retry has to put back
                                    // before it can send the conversation again.
                                    history.drop(sizeBeforeTurn).also { rollbackHistoryTo(sizeBeforeTurn) }
                                } else {
                                    emptyList()
                                }
                                // Which request died is the whole of what the user has to act on,
                                // and the provider message never says: "request timed out" reads as
                                // the prompt having failed when what timed out was the round of tool
                                // results five calls later.
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

        /**
         * Frames the failed request in red and puts a Retry button under it.
         *
         * [dropped] is whatever the failure path took back out of the conversation -- the user's
         * message, when the turn died before anything else happened, and nothing at all when it got
         * far enough to have tool results worth keeping. Retry is what puts it back.
         */
        private fun offerRetry(sizeBeforeTurn: Int, dropped: List<ChatMessage>) {
            transcript.markRequestFailed { retryTurn(sizeBeforeTurn, dropped) }
        }

        /**
         * Sends the failed request again, from the point the failure left the conversation at.
         *
         * Two cases, which is why [dropped] is carried this far. A turn that died on its opening
         * request had that request rolled back, so retrying means putting it back and sending from
         * where it stood -- what the user sees resent is the message their bubble still shows. A turn
         * that died later kept everything it had done, tool results included, so there is nothing to
         * restore and retrying is [continueTurn]'s bargain: send the conversation as it now stands.
         */
        private fun retryTurn(sizeBeforeTurn: Int, dropped: List<ChatMessage>) {
            // Raced by a turn that started in the meantime -- Retry pressed just after something
            // else was sent. The offer is stale rather than wrong, so it is dropped quietly.
            if (!sendButton.isEnabled) return

            if (dropped.isNotEmpty() && history.size == sizeBeforeTurn) {
                history.addAll(dropped)
                startTurn(sizeBeforeTurn)
            } else {
                startTurn(history.size)
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

        /**
         * Offers one withheld output back to the conversation: an Approve button on the card that
         * produced it, and the turn's Continue button once there is something to continue from.
         *
         * On the card rather than under the transcript, because a response can ask for six tools and
         * have more than one of them overrun -- and every one of those six ran, so the transcript
         * now holds several outputs and one link at the bottom could not say which it meant.
         *
         * The output is written to `.cache` but not opened: only the user can say which part of a
         * 40,000-token `find_in_files` was the part worth having, and Edit is how they get at it,
         * but opening a tab per oversized call would bury the chat. A failure to write it costs the
         * Edit button and nothing else -- Approve still sends what the tool actually returned, which
         * is the whole of it.
         */
        private fun offerApproval(toolName: String, toolUseId: String, output: String, tokens: Int, limit: Int) {
            // One line and no markup: an error row is a plain label, so it neither wraps nor renders
            // anything. The output itself is in the card above, and the limit is in
            // Settings | AICodingAgent -- neither needs saying here.
            showError(
                "Stopped: $toolName returned ${"%,d".format(tokens)} tokens, over the " +
                    "${"%,d".format(limit)}-token limit, so it was not sent to the model.",
            )
            // Offered whether or not the card is still there to hang it off: Continue with the
            // withheld notes as they stand is a perfectly good answer, and it is the model narrowing
            // its own call that the note asks for.
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

        /**
         * Puts a withheld output back where the note stands, and says whether it got there.
         *
         * Nothing is added to the conversation: the `tool_result` that is already in it is rewritten,
         * so the model reads the output as what the tool returned rather than as a second-hand
         * quotation of it. That is safe because the request carrying the note never went out -- the
         * turn ended first -- so this edits something the provider has never seen and no cached
         * prefix covers. [settleApprovals] is what stops it being done after that stops being true.
         *
         * The edited file wins when there is one, so Edit and Approve are one action in two steps:
         * trim it, then send it. What is left in the file goes as it stands, over the limit or not
         * -- the limit guards against a tool asking for too much, and has no business overruling
         * someone who has just read the output and decided what of it matters. An empty file is read
         * as "I did not edit this after all" rather than as an instruction to send nothing.
         */
        private fun approveWithheldOutput(
            toolName: String,
            toolUseId: String,
            output: String,
            file: Path?,
        ): Boolean {
            // Pressed while a turn is running -- a click that raced the conversation moving on.
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

        /**
         * Sends the conversation again from where the limit stopped the turn -- the Continue button.
         *
         * Nothing is added: the history already holds a result for every call the round made, either
         * the real output where it was approved or the note saying it was withheld. So the model
         * picks up with what it asked for, and narrows the calls it could not have.
         */
        private fun continueTurn() {
            if (!sendButton.isEnabled) return
            // From here rather than from before the tool calls: everything in the history is being
            // kept, approvals included, so there is nothing for a failed turn to roll back.
            startTurn(history.size)
        }

        /**
         * Closes any approvals still open, because what they offered is no longer safe to do.
         *
         * Called as a turn starts. From that point the withheld notes are on their way to the
         * provider, and replacing what one of them stands for would be rewriting a block that has
         * been sent and cached -- so the buttons say so rather than staying live and lying.
         */
        private fun settleApprovals() {
            withheldCards.values.forEach { it.closeApproval("Sent as withheld") }
            withheldCards.clear()
            // The offer goes with them: what Continue was offering to continue from is being sent.
            transcript.setTurnContinuable(false)
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
            // Stored last, after everything the turn drew and before whatever the user says next, so
            // replaying the rows in order puts it back on the same bubble it was under. Written here
            // rather than as it climbs because the row is what a reopened chat is rebuilt from, and
            // one row per request would draw five costs under one reply.
            // Taken rather than read, so a turn that manages to end twice -- a cancel racing the
            // pooled thread unwinding -- leaves one row and not two. What is drawn is unaffected:
            // the transcript is holding the figure itself by now.
            turnCost?.let {
                rows += StoredRow(
                    StoredRow.COST,
                    text = it.toPlainString(),
                    name = turnCostModel,
                    summary = turnCostRequests.toString(),
                )
                turnCost = null
            }
            // The model has nothing left to do, so its bubble is closed: the next thing it says
            // belongs to a new turn, not to this one.
            transcript.endAiTurn()
            setBusy(false)
            saveCurrentChat(active = true)
        }

        // --- what a reply cost -------------------------------------------------------------------

        /**
         * The reply being drawn: what it has cost so far, on which model, over how many requests.
         *
         * Per turn rather than per request, because a turn is what the user sees as one reply --
         * however many tool-call round trips went into it, and each one re-sends the conversation.
         * Showing a figure per request would put five numbers under one answer and none of them
         * would be the one worth knowing.
         *
         * EDT only, like the transcript it draws into.
         */
        private var turnCost: BigDecimal? = null
        private var turnCostModel = ""
        private var turnCostRequests = 0

        /** Starts a fresh reply with no cost against it, and clears whatever the last one showed. */
        private fun beginTurnCost() {
            turnCost = null
            turnCostModel = ""
            turnCostRequests = 0
            transcript.setTurnCost(null)
        }

        /**
         * Adds one request to what this reply has cost, and redraws the line under it.
         *
         * A model with no price simply adds nothing and leaves the line as it was: half a reply's
         * cost drawn as if it were all of it would be worse than the honest blank, and this is the
         * same rule the `cost_usd` column follows.
         */
        private fun addTurnCost(model: String, reported: AICodingAgentUsage) {
            val cost = ModelPricing.costUsd(
                model,
                reported.input_tokens,
                reported.cache_creation_input_tokens,
                reported.cache_read_input_tokens,
                reported.output_tokens,
            ) ?: return
            val total = (turnCost ?: BigDecimal.ZERO).add(cost)
            turnCost = total
            turnCostModel = model
            turnCostRequests++
            transcript.setTurnCost(costLabel(total), costTooltip(model, turnCostRequests))
        }

        /** `≈` because it is: list prices, and only what the token counts cover. */
        private fun costLabel(cost: BigDecimal): String = "≈ ${ModelPricing.format(cost)}"

        private fun costTooltip(model: String, requests: Int): String = buildString {
            append("<html>Estimated cost of this reply")
            if (model.isNotBlank()) append(" on $model")
            append(", over $requests request(s).<br><br>")
            append("Worked out from the tokens the provider reported, at its published list ")
            append("prices. It is an estimate, not a bill: discounts, negotiated rates and ")
            append("anything charged outside the token counts are not in it.</html>")
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

        /**
         * Show how much of the context window this conversation is using.
         *
         * [used] is the agent's figure: the last request's prompt as the provider counted it, plus
         * whatever has been added to the history since. A [window] of zero is a configuration that
         * does not declare one, and then only the absolute figure is shown -- a percentage of an
         * unknown is worse than no percentage.
         */
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

        private fun promptForMissingApiKey(configuration: AgentConfiguration) {
            val message = configuration.tokenEnvVar?.let { variable ->
                "The \"${configuration.name}\" configuration reads its token from the $variable " +
                    "environment variable, which is empty or undefined. Set it and restart the IDE, " +
                    "which only sees the variables it was launched with."
            } ?: "The \"${configuration.name}\" configuration in ${AgentConfiguration.FILE_NAME} has " +
                "no token. Put one there, or write \$NAME to read it from an environment variable."

            val openSettings = Messages.showYesNoDialog(
                project,
                message,
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

            // Where the context meter turns red. Past compaction's own threshold, so it means what
            // compaction has not been able to fix: what is left is the protected tail and the
            // summary, and the next thing to go is the conversation itself.
            private const val CONTEXT_FULL = 0.85
        }
    }
}
