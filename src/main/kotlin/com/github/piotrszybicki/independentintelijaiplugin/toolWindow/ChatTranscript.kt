package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable
import javax.swing.SwingUtilities

internal class ChatTranscript(
    private val project: Project,
    onCancel: () -> Unit,
    private val onContinue: () -> Unit = {},
) {

    private val rows = mutableListOf<ChatRow>()
    private val placeholder = PlaceholderRow()
    private val thinkingRow = ThinkingRow(onCancel)
    private var lastAppliedWidth = -1

    var onReturnSummary: ((String) -> Boolean)? = null

    private var currentTurn: AiTurnRow? = null

    private var lastTurn: AiTurnRow? = null

    private var lastUserRow: UserRow? = null

    private var lastToolGroup: ToolGroupRow? = null

    private var failedRequest: FailedRequest? = null

    private var lastErrorRow: ErrorRow? = null

    private var retryOffered: ErrorRow? = null

    private val content = TranscriptPanel().apply {
        isOpaque = true
        background = ChatColors.background
        border = JBUI.Borders.empty(12, 12, 12, 8)
        add(placeholder)
    }

    private val scrollPane = JBScrollPane(content).apply {
        border = JBUI.Borders.empty()
        viewportBorder = JBUI.Borders.empty()
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        background = ChatColors.background
        viewport.background = ChatColors.background
    }

    val component: JComponent get() = scrollPane

    init {
        content.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                val width = contentWidth()
                if (width <= 0 || width == lastAppliedWidth) return
                lastAppliedWidth = width
                rows.forEach { it.applyAvailableWidth(width) }
                content.revalidate()
                content.repaint()
            }
        })
    }

    enum class ToolStatus { RUNNING, DONE, FAILED, CANCELLED }

    interface RunningTool {

        fun finish(details: String, status: ToolStatus)

        fun offerApproval(tokens: Int, limit: Int, onApprove: () -> Boolean, onEdit: (() -> Unit)?)

        fun closeApproval(label: String)
    }

    fun addUserMessage(markdown: String) {
        endAiTurn()
        clearRequestFailure()
        lastToolGroup = null
        addRow(UserRow(markdown).also { lastUserRow = it })
    }

    fun addHandoff(
        agentName: String,
        description: String,
        specName: String,
        state: String,
        onOpenSpec: () -> Unit,
        onProceed: () -> Boolean,
        onCancel: () -> Unit,
        onOpenChat: () -> Unit,
    ) {
        endAiTurn()
        lastToolGroup = null
        addRow(
            AgentHandoffRow(
                agentName, description, specName, state,
                onOpenSpec = onOpenSpec,
                onProceed = onProceed,
                onCancel = onCancel,
                onOpenChat = onOpenChat,
            )
        )
    }

    fun lastTurnMarkdown(): String = lastTurn?.toMarkdown().orEmpty()

    fun markRequestFailed(onRetry: () -> Unit) {
        val request = lastToolGroup ?: lastUserRow
        request?.markFailed(onRetry)
        failedRequest = request
        lastErrorRow?.let {
            it.offerRetry(onRetry)
            retryOffered = it
        }
        content.revalidate()
        content.repaint()
    }

    fun clearRequestFailure() {
        failedRequest?.clearFailure()
        failedRequest = null
        retryOffered?.clearRetry()
        retryOffered = null
        content.revalidate()
        content.repaint()
    }

    fun addAssistantMessage(markdown: String) = intoAiTurn(AssistantRow(markdown))

    fun addToolCall(
        requestId: String,
        name: String,
        summary: String,
        details: String,
        status: ToolStatus = ToolStatus.DONE,
    ): RunningTool = ToolRow(name, summary, details, status).also { intoToolGroup(requestId, it) }

    fun startToolCall(requestId: String, name: String, summary: String, details: String): RunningTool =
        ToolRow(name, summary, details, ToolStatus.RUNNING).also { intoToolGroup(requestId, it) }

    fun endAiTurn() {
        currentTurn = null
        pendingCost = null
        closeToolGroup()
    }

    fun setTurnContinuable(offered: Boolean) {
        (currentTurn ?: lastTurn)?.setContinuable(offered)
        content.revalidate()
        content.repaint()
    }

    fun setTurnCost(text: String?, tooltip: String? = null) {
        val turn = currentTurn
        if (turn == null) {
            pendingCost = text?.let { it to tooltip }
            return
        }
        turn.setCost(text, tooltip)
        content.revalidate()
        content.repaint()
    }

    private var pendingCost: Pair<String, String?>? = null

    fun addError(message: String) {
        closeToolGroup()
        addRow(ErrorRow(message).also { lastErrorRow = it })
    }

    private fun intoAiTurn(row: ChatRow) {
        closeToolGroup()
        placeInAiTurn(row)
    }

    private fun placeInAiTurn(row: ChatRow) {
        val turn = currentTurn
        if (turn == null) {
            addRow(
                AiTurnRow(
                    row,
                    onExport = { markdown -> TranscriptExport.save(project, markdown) },
                    onContinue = onContinue,
                    onReturn = onReturnSummary,
                ).also { fresh ->
                    currentTurn = fresh
                    lastTurn = fresh
                    pendingCost?.let { (text, tooltip) -> fresh.setCost(text, tooltip) }
                }
            )
            return
        }
        turn.addContent(row)
        refreshTurn(turn)
    }

    private var currentGroup: ToolGroupRow? = null
    private var currentGroupRequestId = ""

    private fun intoToolGroup(requestId: String, row: ChatRow) {
        if (requestId.isEmpty()) {
            intoAiTurn(row)
            return
        }

        val open = currentGroup?.takeIf { currentGroupRequestId == requestId }
        if (open == null) {
            val fresh = ToolGroupRow(row)
            placeInAiTurn(fresh)
            currentGroup = fresh
            lastToolGroup = fresh
            currentGroupRequestId = requestId
            return
        }

        open.addTool(row)
        currentTurn?.let { refreshTurn(it) }
    }

    private fun closeToolGroup() {
        currentGroup = null
        currentGroupRequestId = ""
    }

    private fun refreshTurn(turn: AiTurnRow) {
        contentWidth().takeIf { it > 0 }?.let { turn.applyAvailableWidth(it) }
        content.revalidate()
        content.repaint()
        scrollToBottom()
    }

    fun setCancellable(cancellable: Boolean) = thinkingRow.setCancellable(cancellable)

    fun setThinking(thinking: Boolean) {
        if (thinking) thinkingRow.setCancellable(true)
        if (thinking == (thinkingRow.parent != null)) return
        if (thinking) {
            content.add(thinkingRow)
        } else {
            content.remove(thinkingRow)
        }
        content.revalidate()
        content.repaint()
        if (thinking) scrollToBottom()
    }

    fun clear() {
        rows.clear()
        currentTurn = null
        lastTurn = null
        lastUserRow = null
        lastToolGroup = null
        failedRequest = null
        lastErrorRow = null
        retryOffered = null
        pendingCost = null
        closeToolGroup()
        content.removeAll()
        content.add(placeholder)
        content.revalidate()
        content.repaint()
    }

    val isEmpty: Boolean get() = rows.isEmpty()

    private fun addRow(row: ChatRow) {
        if (rows.isEmpty()) content.remove(placeholder)

        val thinkingWasVisible = thinkingRow.parent != null
        if (thinkingWasVisible) content.remove(thinkingRow)
        content.add(row)
        if (thinkingWasVisible) content.add(thinkingRow)

        rows += row
        contentWidth().takeIf { it > 0 }?.let { row.applyAvailableWidth(it) }
        content.revalidate()
        content.repaint()
        scrollToBottom()
    }

    private fun contentWidth(): Int = content.width - content.insets.left - content.insets.right

    private fun scrollToBottom() {
        SwingUtilities.invokeLater {
            content.validate()
            val bar = scrollPane.verticalScrollBar
            bar.value = bar.maximum
        }
    }

    private class TranscriptPanel :
        JPanel(VerticalLayout(ChatMetrics.rowGap, VerticalLayout.FILL)),
        Scrollable {

        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = visibleRect.height
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }
}
