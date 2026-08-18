package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JPanel

internal class ToolGroupRow(first: ChatRow) : ChatRow(BorderLayout()), FailedRequest {

    private val tools = mutableListOf<ChatRow>()

    private var availableWidth = -1

    private val stack = JPanel(VerticalLayout(JBUI.scale(4), VerticalLayout.FILL)).apply {
        isOpaque = false
    }

    private val header = JBLabel().apply {
        font = JBFont.small()
        foreground = ChatColors.muted
        toolTipText = "Asked for together, in a single model response"
    }

    private var failed = false

    private var onRetry: (() -> Unit)? = null

    private val retry = CardButton("Retry", "Send these tool results to the model again") {
        onRetry?.invoke()
    }

    private val actions = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
        isOpaque = false
        isVisible = false
        add(retry.component)
    }

    private val box = RoundedPanel(
        BorderLayout(0, JBUI.scale(4)),
        arc = { ChatMetrics.smallArc },
        fill = { ChatColors.toolGroup },
        stroke = { if (failed) ChatColors.error else ChatColors.toolGroupBorder },
        strokeWidth = { if (failed) JBUI.scale(2).toFloat() else 1f },
    ).apply {
        border = JBUI.Borders.empty(JBUI.scale(6))
        add(header, BorderLayout.NORTH)
        add(stack, BorderLayout.CENTER)
        add(actions, BorderLayout.SOUTH)
    }

    override fun markFailed(action: () -> Unit) {
        onRetry = action
        failed = true
        actions.isVisible = true
        revalidate()
        repaint()
    }

    override fun clearFailure() {
        if (!failed) return
        onRetry = null
        failed = false
        actions.isVisible = false
        revalidate()
        repaint()
    }

    init {
        add(box, BorderLayout.CENTER)
        addTool(first)
    }

    fun addTool(row: ChatRow) {
        tools += row
        stack.add(row)
        header.text = if (tools.size == 1) "1 tool call" else "${tools.size} tool calls"
        if (availableWidth > 0) row.applyAvailableWidth(innerWidth(availableWidth))
    }

    override fun applyAvailableWidth(width: Int) {
        availableWidth = width
        val inner = innerWidth(width)
        tools.forEach { it.applyAvailableWidth(inner) }
    }

    private fun innerWidth(width: Int) = width - 2 * JBUI.scale(6)

    override fun toMarkdown(): String? =
        tools.mapNotNull { it.toMarkdown() }.joinToString("\n").takeIf { it.isNotEmpty() }
}
