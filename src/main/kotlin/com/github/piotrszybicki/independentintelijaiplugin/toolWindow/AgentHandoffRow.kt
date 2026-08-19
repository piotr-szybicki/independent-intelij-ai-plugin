package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.github.piotrszybicki.independentintelijaiplugin.agents.AgentHandoff
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JPanel

internal class AgentHandoffRow(
    private val agentName: String,
    description: String,
    private val specName: String,
    state: String,
    onOpenSpec: () -> Unit,
    onProceed: () -> Boolean,
    onCancel: () -> Unit,
    onOpenChat: () -> Unit,
) : ChatRow(BorderLayout()) {

    private val body = HtmlTextPane()

    private val note = JBLabel().apply {
        font = JBFont.small()
        foreground = ChatColors.muted
        toolTipText = "The whole of this file is what the agent is started with"
    }

    private val openSpec = CardButton("Open spec", "Edit the specification before handing it over", onOpenSpec)

    private val proceed = CardButton(
        "Proceed",
        "Start @$agentName in a new chat with this specification",
    ) { if (onProceed()) settle(AgentHandoff.PROCEEDED) }

    private val cancel = CardButton("Cancel", "Drop this hand-off and stay in this chat") {
        onCancel()
        settle(AgentHandoff.CANCELLED)
    }

    private val openChat = CardButton("Open agent chat", "Go to the chat @$agentName is running in", onOpenChat)

    private val actions = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
        isOpaque = false
        add(openSpec.component)
        add(proceed.component)
        add(cancel.component)
        add(openChat.component)
    }

    private val stack = JPanel(VerticalLayout(JBUI.scale(4), VerticalLayout.FILL)).apply {
        isOpaque = false
        add(body)
        add(note)
        add(actions)
    }

    init {
        border = JBUI.Borders.emptyRight(ChatMetrics.bubbleIndent)
        body.setHtml(
            "<b>Hand off to @${escape(agentName)}</b><br><span>${escape(description)}</span>",
        )
        add(
            RoundedPanel(
                BorderLayout(0, JBUI.scale(3)),
                fill = { ChatColors.userBubble },
                stroke = { ChatColors.userBubbleBorder },
            ).apply {
                border = JBUI.Borders.empty(ChatMetrics.bubblePadding)
                add(
                    JBLabel("AGENT HAND-OFF").apply {
                        font = JBFont.small().asBold()
                        foreground = ChatColors.accent
                    },
                    BorderLayout.NORTH,
                )
                add(stack, BorderLayout.CENTER)
            },
            BorderLayout.CENTER,
        )
        settle(state)
    }

    private fun settle(state: String) {
        val draft = state == AgentHandoff.DRAFT
        openSpec.isVisible = state != AgentHandoff.CANCELLED
        proceed.isVisible = draft
        cancel.isVisible = draft
        openChat.isVisible = state == AgentHandoff.PROCEEDED
        note.text = when (state) {
            AgentHandoff.PROCEEDED -> "Handed off — $specName"
            AgentHandoff.CANCELLED -> "Cancelled — $specName"
            else -> "Spec: $specName — edit it, then press Proceed"
        }
        revalidate()
        repaint()
    }

    override fun applyAvailableWidth(width: Int) {
        body.applyWidth(width - ChatMetrics.bubbleIndent - 2 * ChatMetrics.bubblePadding)
    }

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
