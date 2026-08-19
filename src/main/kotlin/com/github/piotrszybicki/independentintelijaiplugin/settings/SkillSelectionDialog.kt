package com.github.piotrszybicki.independentintelijaiplugin.settings

import com.github.piotrszybicki.independentintelijaiplugin.skills.SkillDefinition
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

class SkillSelectionDialog(
    project: Project?,
    private val skills: List<SkillDefinition>,
    selected: Set<String>,
) : DialogWrapper(project) {

    private val allInitially = selected.isEmpty()

    private val boxes: Map<String, JBCheckBox> = skills.associate { skill ->
        skill.name to JBCheckBox(skill.name, allInitially || skill.name in selected)
    }

    private val summary = JBLabel()

    val selectedSkills: Set<String>
        get() = boxes.filterValues { it.isSelected }.keys

    init {
        title = "Select Skills"
        setOKButtonText("Use These Skills")
        wireListeners()
        init()
        refreshSummary()
    }

    override fun createCenterPanel(): JComponent {
        val content = panel {
            row {
                comment(
                    "Every selected skill's name and description is sent with every message. " +
                        "A skill that is not selected will not be known to the model.",
                )
            }
            row {
                button("Select All") { select(skills.mapTo(mutableSetOf()) { it.name }) }
                button("Clear") { select(emptySet()) }
            }
            for (skill in skills) {
                val desc = skill.description.trim()
                val escaped = StringUtil.escapeXmlEntities(
                    if (desc.length > 200) desc.take(200) + "…" else desc,
                ).ifBlank { "No description." }
                row {
                    cell(boxes.getValue(skill.name))
                }.rowComment(escaped)
            }
        }

        return JPanel(BorderLayout()).apply {
            add(JBScrollPane(content).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
            add(summary.apply { border = JBUI.Borders.emptyTop(8) }, BorderLayout.SOUTH)
            preferredSize = JBUI.size(560, 560)
        }
    }

    private fun wireListeners() {
        boxes.values.forEach { box ->
            box.addActionListener { refreshSummary() }
        }
    }

    private fun select(names: Set<String>) {
        boxes.forEach { (name, box) -> box.isSelected = name in names }
        refreshSummary()
    }

    private fun refreshSummary() {
        summary.text = "${selectedSkills.size} of ${boxes.size} skills selected"
    }
}
