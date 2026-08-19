package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.InplaceButton
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.LayoutManager
import javax.swing.JPanel
import javax.swing.SwingConstants

internal abstract class ChatRow(layout: LayoutManager) : JPanel(layout) {
    init {
        isOpaque = false
    }

    open fun applyAvailableWidth(width: Int) {}

    open fun toMarkdown(): String? = null
}

internal interface FailedRequest {
    fun markFailed(action: () -> Unit)
    fun clearFailure()
}

internal class PlaceholderRow : ChatRow(VerticalLayout(JBUI.scale(4), VerticalLayout.FILL)) {
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
        add(
            JBLabel("Type @ to hand the work over to an agent", SwingConstants.CENTER).apply {
                font = JBFont.small()
                foreground = ChatColors.muted
            }
        )
    }
}

internal class AssistantRow(private val markdown: String) : ChatRow(BorderLayout()) {

    private val body = HtmlTextPane()

    init {
        body.setHtml(MarkdownRenderer.toHtml(markdown))
        add(body, BorderLayout.CENTER)
    }

    override fun applyAvailableWidth(width: Int) = body.applyWidth(width)

    override fun toMarkdown(): String? = markdown.trim().takeIf { it.isNotEmpty() }
}

internal class ErrorRow(message: String) : ChatRow(BorderLayout(0, JBUI.scale(4))) {

    private var onRetry: (() -> Unit)? = null

    private val retry = CardButton("Retry", "Send this request to the model again") {
        onRetry?.invoke()
    }

    private val actions = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
        isOpaque = false
        isVisible = false
        add(retry.component)
    }

    init {
        add(
            JBLabel(message, AllIcons.General.BalloonError, SwingConstants.LEFT).apply {
                font = JBFont.small()
                foreground = ChatColors.muted
                iconTextGap = JBUI.scale(5)
            },
            BorderLayout.CENTER,
        )
        add(actions, BorderLayout.SOUTH)
    }

    fun offerRetry(action: () -> Unit) {
        onRetry = action
        actions.isVisible = true
        revalidate()
        repaint()
    }

    fun clearRetry() {
        if (!actions.isVisible) return
        onRetry = null
        actions.isVisible = false
        revalidate()
        repaint()
    }
}

internal class ThinkingRow(onCancel: () -> Unit) : ChatRow(BorderLayout(JBUI.scale(6), 0)) {

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
