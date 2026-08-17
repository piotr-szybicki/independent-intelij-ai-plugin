package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.intellij.icons.AllIcons
import com.intellij.ui.AnimatedIcon
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.InplaceButton
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.LayoutManager
import java.awt.Rectangle
import java.awt.datatransfer.StringSelection
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/**
 * Scrollable transcript of a conversation: user messages as tinted bubbles, the model's replies as
 * plain markdown, and tool calls as compact cards that expand on click.
 */
internal class ChatTranscript(private val project: Project, onCancel: () -> Unit) {

    private val rows = mutableListOf<ChatRow>()
    private val placeholder = PlaceholderRow()
    private val thinkingRow = ThinkingRow(onCancel)
    private var lastAppliedWidth = -1

    /** The bubble the AI is currently filling, or null between turns. */
    private var currentTurn: AiTurnRow? = null

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
        // Rows can only compute their wrapped height once they know how wide the transcript is, so
        // the width is pushed down on every resize. Guarded by lastAppliedWidth: re-laying out
        // changes the transcript's height, which would otherwise bounce back as another resize.
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

    /** Where a tool call has got to, as its row draws it. */
    enum class ToolStatus { RUNNING, DONE, FAILED, CANCELLED }

    /** A tool row drawn before its tool has finished. [finish] settles it. */
    interface RunningTool {
        fun finish(details: String, status: ToolStatus)
    }

    fun addUserMessage(markdown: String) {
        endAiTurn()
        addRow(UserRow(markdown))
    }

    fun addAssistantMessage(markdown: String) = intoAiTurn(AssistantRow(markdown))

    fun addToolCall(name: String, summary: String, details: String, status: ToolStatus = ToolStatus.DONE) =
        intoAiTurn(ToolRow(name, summary, details, status))

    /**
     * Draws a tool call that is still running, spinner and all. The row is the same one [finish]
     * later fills in, so the transcript shows what the model is doing while it does it rather than
     * only once it is over.
     */
    fun startToolCall(name: String, summary: String, details: String): RunningTool =
        ToolRow(name, summary, details, ToolStatus.RUNNING).also { intoAiTurn(it) }

    /**
     * Closes the AI's bubble, so whatever it says next opens a new one. Called when the model is
     * done -- one turn is one bubble, however many messages and tool calls went into it.
     */
    fun endAiTurn() {
        currentTurn = null
        pendingCost = null
    }

    /**
     * Puts what the reply has cost so far along the bottom of the AI's bubble. Null clears it.
     *
     * Held rather than dropped when there is no bubble yet: usage for a request is reported before
     * anything the model said in it is drawn, so the first call of a turn almost always arrives
     * before there is anywhere to put it. It is applied to the bubble the turn opens next.
     */
    fun setTurnCost(text: String?, tooltip: String? = null) {
        val turn = currentTurn
        if (turn == null) {
            pendingCost = text?.let { it to tooltip }
            return
        }
        turn.setCost(text, tooltip)
        // The bubble grows by a line the first time this is set, and the rows below it have to move
        // down with it -- the row revalidating on its own is not enough to shift its neighbours.
        content.revalidate()
        content.repaint()
    }

    /** A cost reported before the turn had a bubble, waiting for one. */
    private var pendingCost: Pair<String, String?>? = null

    fun addError(message: String) = addRow(ErrorRow(message))

    /** Puts [row] in the AI's open bubble, opening one if the model has not spoken since the last turn ended. */
    private fun intoAiTurn(row: ChatRow) {
        val turn = currentTurn
        if (turn == null) {
            // A new bubble is a row like any other -- addRow gives it its width and scrolls to it.
            addRow(AiTurnRow(row) { markdown -> TranscriptExport.save(project, markdown) }.also { fresh ->
                currentTurn = fresh
                pendingCost?.let { (text, tooltip) -> fresh.setCost(text, tooltip) }
            })
            return
        }
        turn.addContent(row)
        // The row arrived after the turn was laid out, so it has never been given a width.
        contentWidth().takeIf { it > 0 }?.let { turn.applyAvailableWidth(it) }
        content.revalidate()
        content.repaint()
        scrollToBottom()
    }

    /** Greys out the stop button for work that pressing it would not actually stop. */
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
        pendingCost = null
        content.removeAll()
        content.add(placeholder)
        content.revalidate()
        content.repaint()
    }

    val isEmpty: Boolean get() = rows.isEmpty()

    private fun addRow(row: ChatRow) {
        if (rows.isEmpty()) content.remove(placeholder)

        // Keep the "working" indicator last so it always reads as the tail of the conversation.
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

    /**
     * Vertical stack that always matches the viewport width, so messages wrap instead of scrolling
     * sideways.
     */
    private class TranscriptPanel :
        JPanel(VerticalLayout(ChatMetrics.rowGap, VerticalLayout.FILL)),
        Scrollable {

        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = visibleRect.height
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }

    private abstract class ChatRow(layout: LayoutManager) : JPanel(layout) {
        init {
            isOpaque = false
        }

        /** Called with the width available inside the transcript's padding. */
        open fun applyAvailableWidth(width: Int) {}

        /**
         * What this row contributes to an export, or null when it contributes nothing.
         *
         * The markdown the row was built from rather than the HTML it drew: see [TranscriptExport].
         */
        open fun toMarkdown(): String? = null
    }

    private class PlaceholderRow : ChatRow(VerticalLayout(JBUI.scale(4), VerticalLayout.FILL)) {
        init {
            border = JBUI.Borders.empty(28, 8, 0, 8)
            add(
                JBLabel("Ask AI about this project", SwingConstants.CENTER).apply {
                    font = JBFont.label().asBold()
                    foreground = ChatColors.foreground
                }
            )
            add(
                JBLabel("Attach a selection for context — Enter sends, Shift+Enter adds a line", SwingConstants.CENTER).apply {
                    font = JBFont.small()
                    foreground = ChatColors.muted
                }
            )
        }
    }

    private class UserRow(markdown: String) : ChatRow(BorderLayout()) {

        private val body = HtmlTextPane()

        init {
            border = JBUI.Borders.emptyLeft(ChatMetrics.bubbleIndent)
            body.setHtml(MarkdownRenderer.toHtml(MarkdownRenderer.normalizeQuoteFences(markdown)))
            add(
                RoundedPanel(
                    BorderLayout(),
                    fill = { ChatColors.userBubble },
                    stroke = { ChatColors.userBubbleBorder },
                ).apply {
                    border = JBUI.Borders.empty(ChatMetrics.bubblePadding)
                    add(body, BorderLayout.CENTER)
                },
                BorderLayout.CENTER,
            )
        }

        override fun applyAvailableWidth(width: Int) {
            body.applyWidth(width - ChatMetrics.bubbleIndent - 2 * ChatMetrics.bubblePadding)
        }
    }

    /**
     * One AI turn: every message it wrote and every tool it called, from the model's first word to
     * the point it had nothing left to do, inside a single bubble. The mirror of [UserRow] -- same
     * shape, held off the opposite edge, and neutral rather than accent-tinted.
     */
    private class AiTurnRow(first: ChatRow, onExport: (String) -> Unit) : ChatRow(BorderLayout()) {

        private val contents = mutableListOf<ChatRow>()

        /** Vertical stack, so the turn's messages and tool cards keep the transcript's own rhythm. */
        private val stack = JPanel(VerticalLayout(ChatMetrics.rowGap, VerticalLayout.FILL)).apply {
            isOpaque = false
        }

        /**
         * What this reply cost, along the bottom of the bubble.
         *
         * Small and muted rather than a line of its own: it is a footnote to the answer, and a
         * reader following the conversation should be able to ignore it without effort. Hidden until
         * there is a figure, so a turn on an unpriced model has no empty strip under it.
         */
        private val cost = JBLabel().apply {
            font = JBFont.small()
            foreground = ChatColors.muted
            isVisible = false
            horizontalAlignment = SwingConstants.RIGHT
        }

        /** EDT only, like everything else the transcript draws. */
        fun setCost(text: String?, tooltip: String?) {
            cost.text = text.orEmpty()
            cost.toolTipText = tooltip
            cost.isVisible = !text.isNullOrEmpty()
            revalidate()
            repaint()
        }

        private var exportHovered = false

        /**
         * Saves this reply to a file, because a chat window is a poor place to keep an answer that
         * is worth coming back to.
         *
         * On the turn rather than on the transcript as a whole: one turn is one answer, and it is an
         * answer -- not a conversation -- that gets pasted into a ticket or kept next to the code it
         * describes. A word rather than an icon, because unlike the copy button on a tool card there
         * is nothing about this spot that would suggest what the icon meant.
         */
        private val export = ActionLink().apply {
            text = "Export MD"
            font = JBFont.small()
            toolTipText = "Save this reply as a Markdown file"
            addActionListener { onExport(toMarkdown()) }
            // The card behind it draws the hover, and the pointer is over the link rather than over
            // the card for all but a few pixels of padding.
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) = setExportHovered(true)
                override fun mouseExited(e: MouseEvent) = setExportHovered(false)
            })
        }

        /**
         * The outline that makes the link read as something to press.
         *
         * The same card the tool rows use, at the same arc and the same three colors, so the one
         * pressable thing in a bubble and the ones above it are recognisably the same kind of
         * control -- a second style of button for a single word would be a style to learn for
         * nothing.
         */
        private val exportButton = RoundedPanel(
            BorderLayout(),
            arc = { ChatMetrics.smallArc },
            fill = { if (exportHovered) ChatColors.cardHover else ChatColors.card },
            stroke = { ChatColors.cardBorder },
        ).apply {
            border = JBUI.Borders.empty(JBUI.scale(2), JBUI.scale(7))
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            add(export, BorderLayout.CENTER)
        }

        /** Called from the link's listener, so it resolves after both fields are built. */
        private fun setExportHovered(hovered: Boolean) {
            exportHovered = hovered
            exportButton.repaint()
        }

        /**
         * The strip along the bottom of the bubble: what the reply cost, and the way to keep it.
         *
         * The cost is pushed to the far side rather than left next to the button, so the figure
         * stays where it has always been -- the corner a reader's eye goes to for it.
         */
        private val footer = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(exportButton, BorderLayout.WEST)
            add(cost, BorderLayout.CENTER)
        }

        init {
            border = JBUI.Borders.emptyRight(ChatMetrics.bubbleIndent)
            add(
                RoundedPanel(
                    BorderLayout(0, JBUI.scale(3)),
                    fill = { ChatColors.aiBubble },
                    stroke = { ChatColors.aiBubbleBorder },
                ).apply {
                    border = JBUI.Borders.empty(ChatMetrics.bubblePadding)
                    // One label for the whole turn, where each message used to carry its own.
                    add(
                        JBLabel("AI").apply {
                            font = JBFont.small().asBold()
                            foreground = ChatColors.accent
                        },
                        BorderLayout.NORTH,
                    )
                    add(stack, BorderLayout.CENTER)
                    add(footer, BorderLayout.SOUTH)
                },
                BorderLayout.CENTER,
            )
            addContent(first)
        }

        fun addContent(row: ChatRow) {
            contents += row
            stack.add(row)
        }

        override fun applyAvailableWidth(width: Int) {
            val inner = width - ChatMetrics.bubbleIndent - 2 * ChatMetrics.bubblePadding
            contents.forEach { it.applyAvailableWidth(inner) }
        }

        /** Everything in the bubble, in the order the model produced it. */
        override fun toMarkdown(): String = contents.mapNotNull { it.toMarkdown() }.joinToString("\n\n")
    }

    private class AssistantRow(private val markdown: String) : ChatRow(BorderLayout()) {

        private val body = HtmlTextPane()

        init {
            body.setHtml(MarkdownRenderer.toHtml(markdown))
            add(body, BorderLayout.CENTER)
        }

        override fun applyAvailableWidth(width: Int) = body.applyWidth(width)

        override fun toMarkdown(): String? = markdown.trim().takeIf { it.isNotEmpty() }
    }

    /** One tool invocation, collapsed to a single line until clicked. */
    private class ToolRow(
        /**
         * Not `name`, which is what it was called while it was a constructor parameter.
         *
         * The header below is built inside `apply` blocks on `JPanel`s, and a panel has a `name` of
         * its own from `java.awt.Component`. A parameter is a local and wins that; a property is a
         * member of the outer class and loses to the nearer receiver, so `JBLabel(name)` quietly
         * became `JBLabel(panel.name)` -- null, and an @NotNull failure on every tool row.
         */
        private val toolName: String,
        private val summary: String,
        private var details: String,
        status: ToolStatus,
    ) : ChatRow(BorderLayout()), RunningTool {

        private val chevron = JBLabel(AllIcons.General.ChevronRight)

        /** Spinner while the tool runs, then how it went. */
        private val statusIcon = JBLabel()

        // Selectable so the output can be copied: the card's own click-to-toggle listener is kept
        // off this pane, and it gets a text cursor instead of the card's hand cursor.
        private val detailPane = HtmlTextPane().apply {
            isVisible = false
            isFocusable = true
            cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
        }

        private val copyButton = InplaceButton("Copy the output", AllIcons.Actions.Copy) {
            CopyPasteManager.getInstance().setContents(StringSelection(details))
        }.apply { isVisible = false }

        private var hovered = false
        private var expanded = false
        private var detailsLoaded = false
        private var availableWidth = -1

        // Outlined, because a tool card now sits inside the AI's bubble, and fill alone is too
        // close to the bubble's own to tell them apart.
        private val card = RoundedPanel(
            BorderLayout(0, JBUI.scale(2)),
            arc = { ChatMetrics.smallArc },
            fill = { if (hovered) ChatColors.cardHover else ChatColors.card },
            stroke = { ChatColors.cardBorder },
        ).apply {
            border = JBUI.Borders.empty(JBUI.scale(5), JBUI.scale(7))
        }

        init {
            setStatus(status)

            val header = JPanel(BorderLayout(JBUI.scale(5), 0)).apply {
                isOpaque = false
                add(
                    JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
                        isOpaque = false
                        add(chevron, BorderLayout.WEST)
                        add(statusIcon, BorderLayout.EAST)
                    },
                    BorderLayout.WEST,
                )
                add(
                    JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
                        isOpaque = false
                        add(
                            JBLabel(toolName).apply {
                                font = JBFont.small()
                                foreground = ChatColors.foreground
                            },
                            BorderLayout.WEST,
                        )
                        add(
                            JBLabel(summary).apply {
                                font = JBFont.small()
                                foreground = ChatColors.muted
                                toolTipText = summary.takeIf { it.isNotEmpty() }
                            },
                            BorderLayout.CENTER,
                        )
                    },
                    BorderLayout.CENTER,
                )
                add(copyButton, BorderLayout.EAST)
            }

            card.add(header, BorderLayout.NORTH)
            card.add(detailPane, BorderLayout.CENTER)
            add(card, BorderLayout.CENTER)

            val mouseListener = object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) = toggle()
                override fun mouseEntered(e: MouseEvent) {
                    hovered = true
                    card.repaint()
                }

                override fun mouseExited(e: MouseEvent) {
                    // The pointer may have moved onto a child label, which is still "inside" the card.
                    hovered = card.visibleRect.contains(SwingUtilities.convertPoint(e.component, e.point, card))
                    card.repaint()
                }
            }
            installRecursively(card, mouseListener)
            card.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }

        // The detail pane and the copy button handle their own clicks, so toggling must not be
        // wired onto them — otherwise selecting the output would collapse the card.
        private fun installRecursively(component: JComponent, listener: MouseAdapter) {
            if (component === detailPane || component === copyButton) return
            component.addMouseListener(listener)
            component.components.filterIsInstance<JComponent>().forEach { installRecursively(it, listener) }
        }

        /**
         * The tool has returned: its output joins the card and the spinner gives way to [status].
         *
         * The details are reloaded rather than appended to, because until now they held the call's
         * arguments alone -- an expanded row was showing what the tool was asked to do, and this is
         * where what it did arrives.
         */
        override fun finish(details: String, status: ToolStatus) {
            this.details = details
            detailsLoaded = false
            if (expanded) loadDetails()
            setStatus(status)
            revalidate()
            repaint()
        }

        private fun setStatus(status: ToolStatus) {
            statusIcon.icon = when (status) {
                ToolStatus.RUNNING -> AnimatedIcon.Default.INSTANCE
                ToolStatus.DONE -> AllIcons.General.InspectionsOK
                ToolStatus.FAILED -> AllIcons.General.BalloonError
                ToolStatus.CANCELLED -> AllIcons.General.BalloonWarning
            }
            statusIcon.toolTipText = when (status) {
                ToolStatus.RUNNING -> "Running"
                ToolStatus.DONE -> "Finished"
                ToolStatus.FAILED -> "Failed — open the card for the error"
                ToolStatus.CANCELLED -> "Cancelled"
            }
        }

        private fun toggle() {
            expanded = !expanded
            chevron.icon = if (expanded) AllIcons.General.ChevronDown else AllIcons.General.ChevronRight
            if (expanded && !detailsLoaded) loadDetails()
            detailPane.isVisible = expanded
            copyButton.isVisible = expanded
            revalidate()
            repaint()
        }

        private fun loadDetails() {
            detailsLoaded = true
            detailPane.setHtml("<pre>${escapeHtml(details)}</pre>")
            if (availableWidth > 0) detailPane.applyWidth(detailWidth(availableWidth))
        }

        override fun applyAvailableWidth(width: Int) {
            availableWidth = width
            detailPane.applyWidth(detailWidth(width))
        }

        private fun detailWidth(width: Int) = width - 2 * JBUI.scale(7)

        /**
         * The line, not the output: a quoted note that the call happened.
         *
         * The output is deliberately left out. It is the one part of a turn that can run to
         * megabytes -- a file read, a full test log -- and an export is meant to be the answer in a
         * readable state, not the working that produced it. The card's own copy button is still
         * there for the times the output is what is wanted.
         */
        override fun toMarkdown(): String {
            val what = summary.trim().replace('`', '\'')
            return if (what.isEmpty()) "> 🔧 `$toolName`" else "> 🔧 `$toolName` — $what"
        }

        private fun escapeHtml(text: String) =
            text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }

    private class ErrorRow(message: String) : ChatRow(BorderLayout()) {
        init {
            add(
                JBLabel(message, AllIcons.General.BalloonError, SwingConstants.LEFT).apply {
                    font = JBFont.small()
                    foreground = ChatColors.muted
                    iconTextGap = JBUI.scale(5)
                },
                BorderLayout.CENTER,
            )
        }
    }

    /**
     * The "working" indicator, with the stop button that ends the turn. The button lives here rather
     * than in the composer because it is only ever meaningful while this row is on screen.
     */
    private class ThinkingRow(onCancel: () -> Unit) : ChatRow(BorderLayout(JBUI.scale(6), 0)) {

        private companion object {
            const val ENABLED_TOOLTIP = "Stop — cancel what the AI is doing"
            const val DISABLED_TOOLTIP = "Cannot be stopped from here — this is running in the terminal"
        }

        private var cancellable = true
        private val label = JBLabel("AI is working", AnimatedIcon.Default.INSTANCE, SwingConstants.LEFT).apply {
            font = JBFont.small()
            foreground = ChatColors.muted
            iconTextGap = JBUI.scale(6)
        }

        // The guard is on the callback, not just the icon: a greyed-out InplaceButton still
        // dispatches its click.
        private val stop = InplaceButton(ENABLED_TOOLTIP, AllIcons.Actions.Suspend) {
            if (cancellable) onCancel()
        }

        init {
            add(stop, BorderLayout.WEST)
            add(label, BorderLayout.CENTER)
        }

        fun setCancellable(value: Boolean) {
            if (cancellable == value) return
            cancellable = value
            stop.icon = if (value) AllIcons.Actions.Suspend else IconLoader.getDisabledIcon(AllIcons.Actions.Suspend)
            stop.setActive(value)
            stop.toolTipText = if (value) ENABLED_TOOLTIP else DISABLED_TOOLTIP
            label.text = if (value) "AI is working" else "AI is waiting for the terminal"
            stop.repaint()
        }
    }
}
