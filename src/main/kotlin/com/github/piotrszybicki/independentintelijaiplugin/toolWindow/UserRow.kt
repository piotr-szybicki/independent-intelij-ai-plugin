package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JPanel

internal class UserRow(markdown: String) : ChatRow(BorderLayout()), FailedRequest {

    private val body = HtmlTextPane()

    private var failed = false

    private var onRetry: (() -> Unit)? = null

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
        strokeWidth = { if (failed) JBUI.scale(2).toFloat() else 1f },
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

    override fun markFailed(action: () -> Unit) {
        onRetry = action
        failed = true
        footer.isVisible = true
        revalidate()
        repaint()
    }

    override fun clearFailure() {
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
