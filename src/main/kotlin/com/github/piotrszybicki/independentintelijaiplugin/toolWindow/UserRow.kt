package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.intellij.openapi.project.Project
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JPanel
import javax.swing.event.HyperlinkEvent

internal class UserRow(
    markdown: String,
    project: Project? = null,
) : ChatRow(BorderLayout()),
    FailedRequest {
    private val body = HtmlTextPane()

    private var failed = false

    private var onRetry: (() -> Unit)? = null

    private val retry =
        CardButton("Retry", "Send this request to the model again") {
            onRetry?.invoke()
        }.apply { isVisible = false }

    private val footer =
        JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            isVisible = false
            add(retry.component)
        }

    private val bubble =
        RoundedPanel(
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
        val renderedMarkdown =
            project?.let { AttachmentFileLink.linkLegacyMarkers(it, markdown) } ?: markdown
        body.setHtml(
            MarkdownRenderer.toHtml(
                MarkdownRenderer.normalizeQuoteFences(renderedMarkdown),
            ),
        )
        body.addHyperlinkListener { event ->
            val href = event.description ?: return@addHyperlinkListener
            if (
                event.eventType == HyperlinkEvent.EventType.ACTIVATED &&
                project != null &&
                AttachmentFileLink.isFileLink(href)
            ) {
                AttachmentFileLink.open(project, href)
            }
        }
        add(bubble, BorderLayout.CENTER)
    }

    override fun markFailed(action: () -> Unit) {
        onRetry = action
        failed = true
        retry.isVisible = true
        footer.isVisible = true
        revalidate()
        repaint()
    }

    override fun clearFailure() {
        if (!failed) return
        onRetry = null
        failed = false
        retry.isVisible = false
        footer.isVisible = false
        revalidate()
        repaint()
    }

    override fun applyAvailableWidth(width: Int) {
        body.applyWidth(width - ChatMetrics.bubbleIndent - 2 * ChatMetrics.bubblePadding)
    }
}
