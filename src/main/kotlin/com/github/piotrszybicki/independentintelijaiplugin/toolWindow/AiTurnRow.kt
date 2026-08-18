package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JPanel
import javax.swing.SwingConstants

internal class AiTurnRow(
    first: ChatRow,
    onExport: (String) -> Unit,
    onContinue: () -> Unit,
) : ChatRow(BorderLayout()) {

    private val contents = mutableListOf<ChatRow>()

    private val stack = JPanel(VerticalLayout(ChatMetrics.rowGap, VerticalLayout.FILL)).apply {
        isOpaque = false
    }

    private val cost = JBLabel().apply {
        font = JBFont.small()
        foreground = ChatColors.muted
        isVisible = false
        horizontalAlignment = SwingConstants.RIGHT
    }

    fun setCost(text: String?, tooltip: String?) {
        cost.text = text.orEmpty()
        cost.toolTipText = tooltip
        cost.isVisible = !text.isNullOrEmpty()
        revalidate()
        repaint()
    }

    private val export = CardButton("Export MD", "Save this reply as a Markdown file") {
        onExport(toMarkdown())
    }

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

    override fun toMarkdown(): String = contents.mapNotNull { it.toMarkdown() }.joinToString("\n\n")
}
