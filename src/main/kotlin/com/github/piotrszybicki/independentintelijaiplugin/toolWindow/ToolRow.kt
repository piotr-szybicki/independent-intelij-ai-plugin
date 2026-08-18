package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.InplaceButton
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import javax.swing.JPanel
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

internal class ToolRow(
    private val toolName: String,
    private val summary: String,
    private var details: String,
    status: ChatTranscript.ToolStatus,
) : ChatRow(BorderLayout()), ChatTranscript.RunningTool {

    private val chevron = JBLabel(AllIcons.General.ChevronRight)

    private val statusIcon = JBLabel()

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

    private val approvals = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
        isOpaque = false
        isVisible = false
    }

    private var approveButton: CardButton? = null

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
                hovered = card.visibleRect.contains(SwingUtilities.convertPoint(e.component, e.point, card))
                card.repaint()
            }
        }
        installRecursively(card, mouseListener)
        card.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    private fun installRecursively(component: JComponent, listener: MouseAdapter) {
        if (component === detailPane || component === copyButton || component === approvals) return
        component.addMouseListener(listener)
        component.components.filterIsInstance<JComponent>().forEach { installRecursively(it, listener) }
    }

    override fun finish(details: String, status: ChatTranscript.ToolStatus) {
        this.details = details
        detailsLoaded = false
        if (expanded) loadDetails()
        setStatus(status)
        revalidate()
        repaint()
    }

    override fun offerApproval(tokens: Int, limit: Int, onApprove: () -> Boolean, onEdit: (() -> Unit)?) {
        if (approveButton != null) return

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

    private fun setStatus(status: ChatTranscript.ToolStatus) {
        statusIcon.icon = when (status) {
            ChatTranscript.ToolStatus.RUNNING -> AnimatedIcon.Default.INSTANCE
            ChatTranscript.ToolStatus.DONE -> AllIcons.General.InspectionsOK
            ChatTranscript.ToolStatus.FAILED -> AllIcons.General.BalloonError
            ChatTranscript.ToolStatus.CANCELLED -> AllIcons.General.BalloonWarning
        }
        statusIcon.toolTipText = when (status) {
            ChatTranscript.ToolStatus.RUNNING -> "Running"
            ChatTranscript.ToolStatus.DONE -> "Finished"
            ChatTranscript.ToolStatus.FAILED -> "Failed — open the card for the error"
            ChatTranscript.ToolStatus.CANCELLED -> "Cancelled"
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

    override fun toMarkdown(): String {
        val what = summary.trim().replace('`', '\'')
        return if (what.isEmpty()) "> 🔧 `$toolName`" else "> 🔧 `$toolName` — $what"
    }

    private fun escapeHtml(text: String) =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
