package com.github.piotrszybicki.independentintelijaiplugin.settings

import com.github.piotrszybicki.independentintelijaiplugin.tools.ToolCatalog
import com.github.piotrszybicki.independentintelijaiplugin.tools.ToolCategory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Picks which built-in tools a request carries.
 *
 * Its own dialog rather than a list on the settings page: thirty-odd checkboxes crowd out
 * everything else there, and the choice is one made occasionally and then left alone. Nothing is
 * saved here -- [selectedTools] is read by the caller, which decides what to do with it.
 */
class ToolSelectionDialog(project: Project?, selected: Set<String>) : DialogWrapper(project) {

    private val boxes: Map<String, JBCheckBox> =
        ToolCatalog.entries.associate { it.name to JBCheckBox(it.name, it.name in selected) }

    /**
     * The per-category box in each group header, which ticks and unticks the group.
     *
     * Plain rather than tri-state: a half-filled group reads as unticked, and clicking it fills the
     * group, which is the only thing anyone wants from it.
     */
    private val masters: Map<ToolCategory, JBCheckBox> =
        ToolCategory.entries.associateWith { JBCheckBox(it.displayName) }

    private val summary = JBLabel()

    /** What is ticked right now. Read after [showAndGet] returns true. */
    val selectedTools: Set<String>
        get() = boxes.filterValues { it.isSelected }.keys

    init {
        title = "Select Tools"
        setOKButtonText("Use These Tools")
        wireListeners()
        // Builds the UI, so everything it touches has to exist by now.
        init()
        syncMasters()
        refreshSummary()
    }

    override fun createCenterPanel(): JComponent {
        val content = panel {
            row {
                comment(
                    "Every selected tool's definition is sent with every message, so the set is a " +
                        "standing cost on each turn whether the model uses any of it or not. A " +
                        "tool that is not selected cannot be called at all, so this bounds what " +
                        "the assistant may do as well as what it costs.",
                )
            }
            row {
                button("Read-Only Defaults") { select(ToolCatalog.parse(ToolCatalog.DEFAULT_ENABLED)) }
                button("Select All") { select(ToolCatalog.entries.mapTo(mutableSetOf()) { it.name }) }
                button("Clear") { select(emptySet()) }
            }
            for (category in ToolCategory.entries) {
                group {
                    row { cell(masters.getValue(category)) }
                    indent {
                        for (entry in ToolCatalog.entries.filter { it.category == category }) {
                            row { cell(boxes.getValue(entry.name)) }
                        }
                    }
                }
            }
        }

        // The count sits outside the scroll pane so it stays put while the list is scrolled --
        // it is the one thing here worth watching as boxes are ticked.
        return JPanel(BorderLayout()).apply {
            add(JBScrollPane(content).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
            add(summary.apply { border = JBUI.Borders.emptyTop(8) }, BorderLayout.SOUTH)
            preferredSize = JBUI.size(460, 560)
        }
    }

    /**
     * Listens for clicks rather than for state.
     *
     * `setSelected` fires no ActionEvent, so a group box filling its children -- and children
     * refreshing their group box -- cannot set the other off again in a loop.
     */
    private fun wireListeners() {
        masters.forEach { (category, master) ->
            master.addActionListener {
                boxesIn(category).forEach { it.isSelected = master.isSelected }
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

    private fun boxesIn(category: ToolCategory): List<JBCheckBox> =
        ToolCatalog.entries.filter { it.category == category }.map { boxes.getValue(it.name) }

    private fun syncMasters() =
        masters.forEach { (category, master) -> master.isSelected = boxesIn(category).all { it.isSelected } }

    private fun refreshSummary() {
        summary.text = "${selectedTools.size} of ${boxes.size} tools selected"
    }
}
