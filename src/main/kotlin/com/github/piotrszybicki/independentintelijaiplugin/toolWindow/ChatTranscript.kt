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
import java.awt.FlowLayout
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
internal class ChatTranscript(
    private val project: Project,
    onCancel: () -> Unit,
    /** What the Continue button in a turn's footer does -- see [setTurnContinuable]. */
    private val onContinue: () -> Unit = {},
) {

    private val rows = mutableListOf<ChatRow>()
    private val placeholder = PlaceholderRow()
    private val thinkingRow = ThinkingRow(onCancel)
    private var lastAppliedWidth = -1

    /** The bubble the AI is currently filling, or null between turns. */
    private var currentTurn: AiTurnRow? = null

    /**
     * The last bubble opened, kept after [endAiTurn] has closed it.
     *
     * [setTurnContinuable] is about a turn that has just stopped, and whether it arrives before or
     * after the turn is closed is a race between two `invokeLater`s. This is what makes the answer
     * not matter.
     */
    private var lastTurn: AiTurnRow? = null

    /** The last request the user sent, which is the one [markRequestFailed] is about. */
    private var lastUserRow: UserRow? = null

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

    /** A handle on one tool card, for the things that happen to it after it is drawn. */
    interface RunningTool {

        /** The tool has returned: its output joins the card and the spinner gives way to [status]. */
        fun finish(details: String, status: ToolStatus)

        /**
         * Puts Approve and Edit buttons on the card, for output the limit withheld.
         *
         * On the card rather than at the end of the transcript because the offer is about *this*
         * call: a response can ask for six tools and have two of them overrun, and one link at the
         * bottom of the conversation cannot say which output it would be sending.
         *
         * [onApprove] returns whether the output actually reached the conversation -- the block it
         * belongs to can be gone -- and the button settles into `Approved` only if it did. [onEdit]
         * is null when there is no file to open, which is the one case Edit is not offered.
         */
        fun offerApproval(tokens: Int, limit: Int, onApprove: () -> Boolean, onEdit: (() -> Unit)?)

        /**
         * Takes an open offer away, saying [label] instead. Used when the conversation moves past
         * the withheld output: once the note has gone out to the model, replacing what it stood for
         * would be rewriting something the provider has already been sent and cached.
         */
        fun closeApproval(label: String)
    }

    fun addUserMessage(markdown: String) {
        endAiTurn()
        // Whatever failed before, this is the request a retry would be about now -- so the red frame
        // and the button go with the row that is no longer the last one.
        clearRequestFailure()
        addRow(UserRow(markdown).also { lastUserRow = it })
    }

    /**
     * Marks the last request as one the model never answered: its bubble is framed in red and a
     * Retry button appears under it.
     *
     * On the request rather than beside the error line, because what a retry sends is that message
     * -- and after a long turn the error can be a screen away from the thing it is about.
     *
     * Does nothing when the conversation is being replayed from disk or was started by something
     * other than a user message: there is no row to mark, and [onRetry] is simply never wired up.
     */
    fun markRequestFailed(onRetry: () -> Unit) {
        val row = lastUserRow ?: return
        row.markFailed(onRetry)
        content.revalidate()
        content.repaint()
    }

    /** Takes the mark and the button away -- the retry has been taken, or the offer has expired. */
    fun clearRequestFailure() {
        val row = lastUserRow ?: return
        row.clearFailure()
        content.revalidate()
        content.repaint()
    }

    fun addAssistantMessage(markdown: String) = intoAiTurn(AssistantRow(markdown))

    /**
     * Draws a finished tool call, and hands back the card so anything that happens to it afterwards
     * -- an offer to approve output the limit withheld -- has something to happen to.
     *
     * [requestId] is the request whose response asked for this call. Every call the model asked for
     * at once carries the same one and they are drawn together in a box of their own; a blank one
     * is drawn on its own, which is what the rows that borrow this shape without being tool calls
     * -- a thinking summary, a compaction pass -- pass.
     */
    fun addToolCall(
        requestId: String,
        name: String,
        summary: String,
        details: String,
        status: ToolStatus = ToolStatus.DONE,
    ): RunningTool = ToolRow(name, summary, details, status).also { intoToolGroup(requestId, it) }

    /**
     * Draws a tool call that is still running, spinner and all. The row is the same one [finish]
     * later fills in, so the transcript shows what the model is doing while it does it rather than
     * only once it is over.
     */
    fun startToolCall(requestId: String, name: String, summary: String, details: String): RunningTool =
        ToolRow(name, summary, details, ToolStatus.RUNNING).also { intoToolGroup(requestId, it) }

    /**
     * Closes the AI's bubble, so whatever it says next opens a new one. Called when the model is
     * done -- one turn is one bubble, however many messages and tool calls went into it.
     */
    fun endAiTurn() {
        currentTurn = null
        pendingCost = null
        closeToolGroup()
    }

    /**
     * Shows or hides the Continue button in the last turn's footer, beside Export MD.
     *
     * Shown only when a turn stopped on something the user can still act on -- the tool output limit
     * -- and not at the end of every reply: Continue means "send the conversation as it now stands",
     * which on a turn that simply finished would be a button for asking the model to carry on
     * talking to itself. Taken away again once the conversation has been sent, so the offer never
     * outlives what it was offering.
     *
     * Idempotent, so a round with several withheld outputs shows one button rather than one each.
     */
    fun setTurnContinuable(offered: Boolean) {
        (currentTurn ?: lastTurn)?.setContinuable(offered)
        content.revalidate()
        content.repaint()
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

    /**
     * An error, drawn at the transcript's level rather than inside the turn's bubble.
     *
     * It closes any open group for the same reason [intoAiTurn] does, and it matters more here:
     * this row goes *below* the bubble, so a tool call that rejoined the group afterwards would be
     * drawn above something that happened before it.
     */
    fun addError(message: String) {
        closeToolGroup()
        addRow(ErrorRow(message))
    }

    /** Puts [row] in the AI's open bubble, opening one if the model has not spoken since the last turn ended. */
    private fun intoAiTurn(row: ChatRow) {
        // Anything that is not a tool call ends the run of them: the model has moved on to saying
        // something, and a group left open across it would draw the calls that follow inside the
        // box that came before them.
        closeToolGroup()
        placeInAiTurn(row)
    }

    /** [intoAiTurn] without closing the open group, so the group itself can be placed by one. */
    private fun placeInAiTurn(row: ChatRow) {
        val turn = currentTurn
        if (turn == null) {
            // A new bubble is a row like any other -- addRow gives it its width and scrolls to it.
            addRow(
                AiTurnRow(
                    row,
                    onExport = { markdown -> TranscriptExport.save(project, markdown) },
                    onContinue = onContinue,
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

    /**
     * The box holding the tool calls of the request currently being answered, and which request
     * that is. Null and blank between rounds.
     */
    private var currentGroup: ToolGroupRow? = null
    private var currentGroupRequestId = ""

    /**
     * Puts a tool row in the box for [requestId], opening one if this is the round's first call.
     *
     * The grouping is by request rather than by "however many arrived in a row", because the two
     * differ in exactly the case worth drawing: a response that asks for tools without saying
     * anything first leaves nothing between its calls and the previous request's, and reading them
     * as one round would claim the model did in one step what it took two requests to do.
     */
    private fun intoToolGroup(requestId: String, row: ChatRow) {
        // Nothing to group by -- the rows that borrow the tool card's shape without coming from a
        // request, and any chat saved before the id was recorded.
        if (requestId.isEmpty()) {
            intoAiTurn(row)
            return
        }

        val open = currentGroup?.takeIf { currentGroupRequestId == requestId }
        if (open == null) {
            // A box of its own for this request, placed in the bubble as one row -- so a group that
            // opens before the turn has a bubble gets one built around it, the same as any row.
            val fresh = ToolGroupRow(row)
            placeInAiTurn(fresh)
            currentGroup = fresh
            currentGroupRequestId = requestId
            return
        }

        open.addTool(row)
        // The turn as a whole, not the group: the row is two levels down, and it is the turn that
        // knows how much width is left once its bubble's padding is taken off.
        currentTurn?.let { refreshTurn(it) }
    }

    private fun closeToolGroup() {
        currentGroup = null
        currentGroupRequestId = ""
    }

    /** Re-measures [turn] after something was added to it, and keeps the view at the bottom. */
    private fun refreshTurn(turn: AiTurnRow) {
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
        lastTurn = null
        lastUserRow = null
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

    /**
     * The small outlined button the bubble's footer and the tool cards share.
     *
     * One class rather than the four copies this would otherwise be, and worth having as one because
     * the alternative is a second style of button appearing in the transcript the first time someone
     * gets a padding wrong. An [ActionLink] inside a [RoundedPanel] rather than a Swing button: the
     * transcript is a stack of text, and a real button draws as a raised box in the middle of a page.
     *
     * Holds a panel rather than being one, so the hover flag is a field the fill lambda can close
     * over -- a subclass would have to pass that lambda to its own superclass constructor, before
     * the flag exists.
     */
    private class CardButton(text: String, tooltip: String, onClick: () -> Unit) {

        private var hovered = false

        /** False once the offer behind the button is over: it then reads as a label, not a control. */
        private var active = true

        private val link = ActionLink(text) { if (active) onClick() }.apply {
            font = JBFont.small()
            toolTipText = tooltip
            // The card behind it draws the hover, and the pointer is over the link rather than over
            // the card for all but a few pixels of padding.
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) = setHovered(true)
                override fun mouseExited(e: MouseEvent) = setHovered(false)
            })
        }

        val component: JComponent = RoundedPanel(
            BorderLayout(),
            arc = { ChatMetrics.smallArc },
            fill = { if (hovered && active) ChatColors.cardHover else ChatColors.card },
            stroke = { ChatColors.cardBorder },
        ).apply {
            border = JBUI.Borders.empty(JBUI.scale(2), JBUI.scale(7))
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            add(link, BorderLayout.CENTER)
        }

        private fun setHovered(value: Boolean) {
            hovered = value
            component.repaint()
        }

        var isVisible: Boolean
            get() = component.isVisible
            set(value) {
                component.isVisible = value
            }

        /** Turns the button into a note of what happened to it: [text] instead, and nothing to press. */
        fun settle(text: String) {
            active = false
            link.text = text
            link.foreground = ChatColors.muted
            link.toolTipText = null
            component.cursor = Cursor.getDefaultCursor()
            component.repaint()
        }
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

        /** True once the request this message started came back as a failure -- see [markFailed]. */
        private var failed = false

        /**
         * What Retry does, replaced on every failure rather than captured once.
         *
         * A retry that fails again marks this same row a second time, and what has to be re-sent by
         * then is not what had to be re-sent the first time -- the second attempt may have got
         * further into the conversation than the first. The button is built once and reads this.
         */
        private var onRetry: (() -> Unit)? = null

        /**
         * Retry sits inside the bubble, under the message it would send again.
         *
         * Under the request rather than at the end of the transcript for the same reason Approve
         * sits on its tool card: it is about *this* message, and it appears at the moment the frame
         * around that message turns red, so the two read as one thing.
         */
        private val retry = CardButton("Retry", "Send this request to the model again") {
            onRetry?.invoke()
        }

        private val footer = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            isVisible = false
            add(retry.component)
        }

        private val bubble = RoundedPanel(
            BorderLayout(0, JBUI.scale(6)),
            fill = { ChatColors.userBubble },
            stroke = { if (failed) ChatColors.error else ChatColors.userBubbleBorder },
        ).apply {
            border = JBUI.Borders.empty(ChatMetrics.bubblePadding)
            add(body, BorderLayout.CENTER)
            add(footer, BorderLayout.SOUTH)
        }

        init {
            border = JBUI.Borders.emptyLeft(ChatMetrics.bubbleIndent)
            body.setHtml(MarkdownRenderer.toHtml(MarkdownRenderer.normalizeQuoteFences(markdown)))
            add(bubble, BorderLayout.CENTER)
        }

        /** The model never answered this one: red frame, and a way to send it again. */
        fun markFailed(action: () -> Unit) {
            onRetry = action
            failed = true
            footer.isVisible = true
            revalidate()
            repaint()
        }

        /** Back to an ordinary request, because the offer to retry is over or has been taken. */
        fun clearFailure() {
            if (!failed) return
            onRetry = null
            failed = false
            footer.isVisible = false
            revalidate()
            repaint()
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
    private class AiTurnRow(
        first: ChatRow,
        onExport: (String) -> Unit,
        onContinue: () -> Unit,
    ) : ChatRow(BorderLayout()) {

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

        /**
         * Saves this reply to a file, because a chat window is a poor place to keep an answer that
         * is worth coming back to.
         *
         * On the turn rather than on the transcript as a whole: one turn is one answer, and it is an
         * answer -- not a conversation -- that gets pasted into a ticket or kept next to the code it
         * describes. A word rather than an icon, because unlike the copy button on a tool card there
         * is nothing about this spot that would suggest what the icon meant.
         */
        private val export = CardButton("Export MD", "Save this reply as a Markdown file") {
            onExport(toMarkdown())
        }

        /**
         * Sends the conversation again, picking the turn up from where the tool output limit stopped
         * it.
         *
         * Hidden until there is something to continue *from*. Beside Export MD because both are
         * about this reply as a whole rather than about any one thing in it -- and because a turn
         * that stopped on a withheld output has its Approve buttons up in the cards above, so the
         * one control that finishes the job wants to be somewhere fixed rather than beside whichever
         * card happened to be last.
         */
        private val continueTurn = CardButton(
            "Continue",
            "Send the conversation again and carry on from where the limit stopped this turn",
            onContinue,
        ).apply { isVisible = false }

        fun setContinuable(offered: Boolean) {
            continueTurn.isVisible = offered
            revalidate()
            repaint()
        }

        /**
         * The strip along the bottom of the bubble: what the reply cost, and what can be done with it.
         *
         * The cost is pushed to the far side rather than left next to the buttons, so the figure
         * stays where it has always been -- the corner a reader's eye goes to for it.
         */
        private val footer = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(
                JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                    isOpaque = false
                    add(export.component)
                    add(continueTurn.component)
                },
                BorderLayout.WEST,
            )
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

    /**
     * The tool calls of one model response, boxed together inside the turn's bubble.
     *
     * A box inside a box, because the nesting is the thing being shown: a turn is several requests,
     * and each request can ask for several tools at once. Without it a turn that made nine calls is
     * nine identical cards in a column, and nothing on screen says whether that was one round or
     * nine round trips -- which is the difference between a model working in parallel and a model
     * feeling its way one step at a time, and the difference in what the turn cost, since each
     * round re-sends the whole conversation.
     */
    private class ToolGroupRow(first: ChatRow) : ChatRow(BorderLayout()) {

        private val tools = mutableListOf<ChatRow>()

        /**
         * Declared before the `init` below, which adds the first tool and reads it. A property
         * initialised after an `init` block that uses it is still zero when that block runs, and
         * this one is the difference between passing a width down and passing zero down.
         */
        private var availableWidth = -1

        private val stack = JPanel(VerticalLayout(JBUI.scale(4), VerticalLayout.FILL)).apply {
            isOpaque = false
        }

        /** How many calls are in the box, which grows as the round's tools are run one by one. */
        private val header = JBLabel().apply {
            font = JBFont.small()
            foreground = ChatColors.muted
            toolTipText = "Asked for together, in a single model response"
        }

        private val box = RoundedPanel(
            BorderLayout(0, JBUI.scale(4)),
            arc = { ChatMetrics.smallArc },
            fill = { ChatColors.toolGroup },
            stroke = { ChatColors.toolGroupBorder },
        ).apply {
            border = JBUI.Borders.empty(JBUI.scale(6))
            add(header, BorderLayout.NORTH)
            add(stack, BorderLayout.CENTER)
        }

        init {
            add(box, BorderLayout.CENTER)
            addTool(first)
        }

        fun addTool(row: ChatRow) {
            tools += row
            stack.add(row)
            header.text = if (tools.size == 1) "1 tool call" else "${tools.size} tool calls"
            // A width was handed down before this row existed, so it has to be passed on by hand --
            // the group is only asked again when the transcript itself resizes.
            if (availableWidth > 0) row.applyAvailableWidth(innerWidth(availableWidth))
        }

        override fun applyAvailableWidth(width: Int) {
            availableWidth = width
            val inner = innerWidth(width)
            tools.forEach { it.applyAvailableWidth(inner) }
        }

        private fun innerWidth(width: Int) = width - 2 * JBUI.scale(6)

        /** The calls in order, as the quoted lines each of them exports. */
        override fun toMarkdown(): String? =
            tools.mapNotNull { it.toMarkdown() }.joinToString("\n").takeIf { it.isNotEmpty() }
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

        /**
         * Where an offer to approve withheld output goes: along the bottom of the card, under the
         * output it is about. Empty and hidden on every card that never has one, which is nearly all
         * of them.
         */
        private val approvals = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            isVisible = false
        }

        /** The Approve button once there is one, so a second offer is ignored and [closeApproval] can settle it. */
        private var approveButton: CardButton? = null

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
            card.add(approvals, BorderLayout.SOUTH)
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

        // The detail pane, the copy button and the approval strip handle their own clicks, so
        // toggling must not be wired onto them — otherwise selecting the output would collapse the
        // card, and so would approving it.
        private fun installRecursively(component: JComponent, listener: MouseAdapter) {
            if (component === detailPane || component === copyButton || component === approvals) return
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

        override fun offerApproval(tokens: Int, limit: Int, onApprove: () -> Boolean, onEdit: (() -> Unit)?) {
            // One offer per card. The same call cannot overrun twice, but a redraw that reached here
            // again would otherwise stack a second pair of buttons under the first.
            if (approveButton != null) return

            // The click settles the very button being clicked, which it reaches through the field
            // rather than through itself: the field is assigned on the next line and the lambda does
            // not run until someone presses it.
            val approve = CardButton(
                "Approve output",
                "Send all ${"%,d".format(tokens)} tokens of this output to the model as $toolName's " +
                    "result, over the ${"%,d".format(limit)}-token limit",
            ) {
                if (onApprove()) approveButton?.settle("Approved")
            }
            approveButton = approve
            approvals.add(approve.component)

            onEdit?.let { edit ->
                approvals.add(
                    CardButton(
                        "Edit",
                        "Open the output in an editor and cut it down; Approve then sends your version",
                        edit,
                    ).component
                )
            }

            approvals.isVisible = true
            revalidate()
            repaint()
        }

        override fun closeApproval(label: String) {
            approveButton?.settle(label)
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
