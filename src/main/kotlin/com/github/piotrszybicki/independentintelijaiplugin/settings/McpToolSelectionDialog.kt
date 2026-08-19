package com.github.piotrszybicki.independentintelijaiplugin.settings

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

class McpToolSelectionDialog(
    project: Project?,
    private val entries: List<McpToolEntry>,
    selected: Set<String>,
) : DialogWrapper(project) {

    data class McpToolEntry(
        val serverName: String,
        val toolName: String,
        val qualifiedName: String,
        val description: String,
    )

    private val allInitially = selected.isEmpty()

    private val boxes: Map<String, JBCheckBox> = entries.associate { entry ->
        entry.qualifiedName to JBCheckBox(entry.toolName, allInitially || entry.qualifiedName in selected)
    }

    private val servers = entries.map { it.serverName }.distinct()

    private val masters: Map<String, JBCheckBox> = servers.associateWith { JBCheckBox(it) }

    private val summary = JBLabel()

    val selectedTools: Set<String>
        get() = boxes.filterValues { it.isSelected }.keys

    init {
        title = "Select MCP Tools"
        setOKButtonText("Use These Tools")
        wireListeners()
        init()
        syncMasters()
        refreshSummary()
    }

    override fun createCenterPanel(): JComponent {
        val content = panel {
            row {
                comment(
                    "Every selected tool's definition is sent with every message, so the set " +
                        "costs tokens on each turn whether the model calls any or not. A tool " +
                        "that is not selected cannot be called.",
                )
            }
            row {
                button("Select All") { select(entries.mapTo(mutableSetOf()) { it.qualifiedName }) }
                button("Clear") { select(emptySet()) }
            }
            for (server in servers) {
                group {
                    row { cell(masters.getValue(server)) }
                    indent {
                        for (entry in entries.filter { it.serverName == server }) {
                            val desc = entry.description.trim()
                            val escaped = StringUtil.escapeXmlEntities(
                                if (desc.length > 200) desc.take(200) + "…" else desc,
                            ).ifBlank { "No description provided." }
                            row {
                                cell(boxes.getValue(entry.qualifiedName))
                            }.rowComment(escaped)
                        }
                    }
                }
            }
        }

        return JPanel(BorderLayout()).apply {
            add(JBScrollPane(content).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
            add(summary.apply { border = JBUI.Borders.emptyTop(8) }, BorderLayout.SOUTH)
            preferredSize = JBUI.size(560, 560)
        }
    }

    private fun wireListeners() {
        masters.forEach { (server, master) ->
            master.addActionListener {
                boxesIn(server).forEach { it.isSelected = master.isSelected }
                refreshSummary()
            }
        }
        boxes.values.forEach { box ->
            box.addActionListener {
                syncMasters()
                refreshSummary()
            }
        }
    }

    private fun select(names: Set<String>) {
        boxes.forEach { (name, box) -> box.isSelected = name in names }
        syncMasters()
        refreshSummary()
    }

    private fun boxesIn(server: String): List<JBCheckBox> =
        entries.filter { it.serverName == server }.map { boxes.getValue(it.qualifiedName) }

    private fun syncMasters() =
        masters.forEach { (server, master) -> master.isSelected = boxesIn(server).all { it.isSelected } }

    private fun refreshSummary() {
        summary.text = "${selectedTools.size} of ${boxes.size} MCP tools selected"
    }
}
