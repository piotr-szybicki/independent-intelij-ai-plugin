package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.github.piotrszybicki.independentintelijaiplugin.agents.AgentFiles
import com.github.piotrszybicki.independentintelijaiplugin.agents.AgentReturn
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.InplaceButton
import com.intellij.ui.components.JBLabel
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants

internal class AgentReturnsBar(
    private val project: Project,
    private val onOpen: (Path) -> Unit,
    private val onRevalidate: () -> Unit,
) {

    private val pending = mutableListOf<AgentReturn>()

    private val heading = JBLabel().apply {
        font = JBFont.small().asBold()
        foreground = ChatColors.accent
    }

    private val list = JPanel(GridLayout(0, 1, 0, JBUI.scale(3))).apply { isOpaque = false }

    private val scroll = CappedScrollPane(list, 96).apply {
        border = JBUI.Borders.emptyTop(JBUI.scale(4))
        isOpaque = false
        viewport.isOpaque = false
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
    }

    val component: JComponent = RoundedPanel(
        BorderLayout(),
        arc = { ChatMetrics.smallArc },
        fill = { ChatColors.userBubble },
        stroke = { ChatColors.userBubbleBorder },
    ).apply {
        isVisible = false
        border = JBUI.Borders.empty(JBUI.scale(5), JBUI.scale(9))
        add(heading, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
    }

    fun returns(): List<AgentReturn> = pending.toList()

    fun set(returns: List<AgentReturn>) {
        pending.clear()
        pending.addAll(returns)
        refresh()
    }

    fun clear() {
        pending.clear()
        refresh()
    }

    private fun remove(returned: AgentReturn) {
        pending.remove(returned)
        refresh()
    }

    private fun refresh() {
        list.removeAll()
        pending.forEach { list.add(chip(it)) }
        heading.text = if (pending.size == 1) "Returned by an agent" else "Returned by agents"
        component.isVisible = pending.isNotEmpty()
        onRevalidate()
    }

    private fun chip(returned: AgentReturn): JComponent {
        val path = Path.of(returned.path)
        val name = AgentFiles.displayPath(project, path)

        val chip = RoundedPanel(
            BorderLayout(JBUI.scale(6), 0),
            arc = { ChatMetrics.smallArc },
            fill = { ChatColors.card },
            stroke = { ChatColors.separator },
        ).apply {
            border = JBUI.Borders.empty(JBUI.scale(3), JBUI.scale(7))
            add(
                JBLabel("📥 @${returned.agentName}  $name").apply {
                    font = JBFont.small()
                    foreground = ChatColors.foreground
                    toolTipText = "Returned ${DateFormatUtil.formatPrettyDateTime(returned.createdAt)} — " +
                        "click to open it; the file as it stands is what the next message sends"
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent) = onOpen(path)
                    })
                },
                BorderLayout.CENTER,
            )
            add(
                InplaceButton("Drop this summary", AllIcons.Actions.Close) { remove(returned) },
                BorderLayout.EAST,
            )
        }

        return JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(chip)
        }
    }
}
