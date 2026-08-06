package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.intellij.icons.AllIcons
import com.intellij.ui.AnimatedIcon
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.InplaceButton
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
 * Scrollable transcript of a conversation: user messages as tinted bubbles, Claude's replies as
 * plain markdown, and tool calls as compact cards that expand on click.
 */
internal class ChatTranscript(onCancel: () -> Unit) {

    private val rows = mutableListOf<ChatRow>()
    private val placeholder = PlaceholderRow()
    private val thinkingRow = ThinkingRow(onCancel)
    private var lastAppliedWidth = -1

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

    fun addUserMessage(markdown: String) = addRow(UserRow(markdown))

    fun addAssistantMessage(markdown: String) = addRow(AssistantRow(markdown))

    fun addToolCall(name: String, summary: String, details: String) = addRow(ToolRow(name, summary, details))

    fun addError(message: String) = addRow(ErrorRow(message))

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
    }

    private class PlaceholderRow : ChatRow(VerticalLayout(JBUI.scale(4), VerticalLayout.FILL)) {
        init {
            border = JBUI.Borders.empty(28, 8, 0, 8)
            add(
                JBLabel("Ask Claude about this project", SwingConstants.CENTER).apply {
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
            border = JBUI.Borders.emptyLeft(ChatMetrics.userIndent)
            body.setHtml(MarkdownRenderer.toHtml(markdown))
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
            body.applyWidth(width - ChatMetrics.userIndent - 2 * ChatMetrics.bubblePadding)
        }
    }

    private class AssistantRow(markdown: String) : ChatRow(BorderLayout(0, JBUI.scale(3))) {

        private val body = HtmlTextPane()

        init {
            add(
                JBLabel("Claude").apply {
                    font = JBFont.small().asBold()
                    foreground = ChatColors.accent
                },
                BorderLayout.NORTH,
            )
            body.setHtml(MarkdownRenderer.toHtml(markdown))
            add(body, BorderLayout.CENTER)
        }

        override fun applyAvailableWidth(width: Int) = body.applyWidth(width)
    }

    /** One tool invocation, collapsed to a single line until clicked. */
    private class ToolRow(name: String, summary: String, private val details: String) : ChatRow(BorderLayout()) {

        private val chevron = JBLabel(AllIcons.General.ChevronRight)
        private val detailPane = HtmlTextPane().apply { isVisible = false }
        private var hovered = false
        private var expanded = false
        private var detailsLoaded = false
        private var availableWidth = -1

        private val card = RoundedPanel(
            BorderLayout(0, JBUI.scale(2)),
            arc = { ChatMetrics.smallArc },
            fill = { if (hovered) ChatColors.cardHover else ChatColors.card },
        ).apply {
            border = JBUI.Borders.empty(JBUI.scale(5), JBUI.scale(7))
        }

        init {
            val header = JPanel(BorderLayout(JBUI.scale(5), 0)).apply {
                isOpaque = false
                add(chevron, BorderLayout.WEST)
                add(
                    JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
                        isOpaque = false
                        add(
                            JBLabel(name).apply {
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

        private fun installRecursively(component: JComponent, listener: MouseAdapter) {
            component.addMouseListener(listener)
            component.components.filterIsInstance<JComponent>().forEach { installRecursively(it, listener) }
        }

        private fun toggle() {
            expanded = !expanded
            chevron.icon = if (expanded) AllIcons.General.ChevronDown else AllIcons.General.ChevronRight
            if (expanded && !detailsLoaded) {
                detailsLoaded = true
                detailPane.setHtml("<pre>${escapeHtml(details)}</pre>")
                if (availableWidth > 0) detailPane.applyWidth(detailWidth(availableWidth))
            }
            detailPane.isVisible = expanded
            revalidate()
            repaint()
        }

        override fun applyAvailableWidth(width: Int) {
            availableWidth = width
            detailPane.applyWidth(detailWidth(width))
        }

        private fun detailWidth(width: Int) = width - 2 * JBUI.scale(7)

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
            const val ENABLED_TOOLTIP = "Stop — cancel what Claude is doing"
            const val DISABLED_TOOLTIP = "Cannot be stopped from here — this is running in the terminal"
        }

        private var cancellable = true
        private val label = JBLabel("Claude is working", AnimatedIcon.Default.INSTANCE, SwingConstants.LEFT).apply {
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
            label.text = if (value) "Claude is working" else "Claude is waiting for the terminal"
            stop.repaint()
        }
    }
}
