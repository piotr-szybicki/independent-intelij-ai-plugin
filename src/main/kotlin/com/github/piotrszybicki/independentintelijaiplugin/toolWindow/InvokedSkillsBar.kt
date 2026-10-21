package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.github.piotrszybicki.independentintelijaiplugin.skills.SkillDefinition
import com.intellij.icons.AllIcons
import com.intellij.ui.InplaceButton
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.JComponent
import javax.swing.JPanel

/** Skills invoked with / for the message currently being composed. */
internal class InvokedSkillsBar(
    private val onRemove: (SkillDefinition) -> Unit,
    private val onRevalidate: () -> Unit,
) {
    private val pending = mutableListOf<SkillDefinition>()

    private val list = JPanel(GridLayout(0, 1, 0, JBUI.scale(3))).apply { isOpaque = false }

    val component: JComponent =
        JPanel(BorderLayout()).apply {
            isOpaque = false
            isVisible = false
            add(list, BorderLayout.CENTER)
        }

    fun add(skill: SkillDefinition) {
        if (pending.none { it.name == skill.name }) pending += skill
        refresh()
    }

    fun clear() {
        pending.clear()
        refresh()
    }

    private fun remove(skill: SkillDefinition) {
        pending.removeAll { it.name == skill.name }
        onRemove(skill)
        refresh()
    }

    private fun refresh() {
        list.removeAll()
        pending.forEach { skill ->
            val chip =
                RoundedPanel(
                    BorderLayout(JBUI.scale(6), 0),
                    arc = { ChatMetrics.smallArc },
                    fill = { ChatColors.card },
                    stroke = { ChatColors.separator },
                ).apply {
                    border = JBUI.Borders.empty(JBUI.scale(3), JBUI.scale(7))
                    add(
                        JBLabel("🧭 /${skill.name}").apply {
                            font = JBFont.small()
                            foreground = ChatColors.foreground
                            toolTipText = skill.description
                        },
                        BorderLayout.CENTER,
                    )
                    add(
                        InplaceButton("Remove /${skill.name} from this message", AllIcons.Actions.Close) {
                            remove(skill)
                        },
                        BorderLayout.EAST,
                    )
                }
            list.add(
                JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                    isOpaque = false
                    add(chip)
                },
            )
        }
        component.isVisible = pending.isNotEmpty()
        onRevalidate()
    }
}
