package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.github.piotrszybicki.independentintelijaiplugin.agents.AgentSession
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel

internal class AgentBanner(
    private val onOpenParent: (String) -> Unit,
    private val onOpenSpec: () -> Unit,
) {

    private var session: AgentSession? = null

    private val label = JBLabel().apply {
        font = JBFont.small().asBold()
        foreground = ChatColors.accent
    }

    private val parentChat = CardButton(
        "Back to the chat that started it",
        "Open the chat this agent was handed off from",
    ) { session?.parentChatId?.let(onOpenParent) }

    private val spec = CardButton("Spec", "Open the specification this agent was started with", onOpenSpec)

    val component: JComponent = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
        isOpaque = true
        background = ChatColors.mix(ChatColors.background, ChatColors.accent, 0.10)
        border = BorderFactory.createCompoundBorder(
            JBUI.Borders.customLineBottom(ChatColors.separator),
            JBUI.Borders.empty(4, 8),
        )
        isVisible = false
        add(label, BorderLayout.CENTER)
        add(
            JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
                isOpaque = false
                add(spec.component)
                add(parentChat.component)
            },
            BorderLayout.EAST,
        )
    }

    fun show(session: AgentSession?, tools: String? = null) {
        this.session = session
        if (session == null) {
            component.isVisible = false
        } else {
            label.text = "Agent chat — @${session.agentName}"
            label.toolTipText = tools?.let { "Tools in this chat: $it" }
            parentChat.isVisible = session.parentChatId != null
            spec.isVisible = session.specPath != null
            component.isVisible = true
        }
        component.revalidate()
        component.repaint()
    }
}
