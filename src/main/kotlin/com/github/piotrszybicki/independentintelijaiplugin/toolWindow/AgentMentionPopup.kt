package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.github.piotrszybicki.independentintelijaiplugin.agents.AgentCatalog
import com.github.piotrszybicki.independentintelijaiplugin.agents.AgentDefinition
import com.github.piotrszybicki.independentintelijaiplugin.settings.AgentConfigurations
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import java.awt.Point
import javax.swing.JList
import javax.swing.JTextPane
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

internal class AgentMentionPopup(
    private val project: Project,
    private val input: JTextPane,
    private val onChosen: (AgentDefinition) -> Unit,
) {

    private var popup: JBPopup? = null

    fun install() {
        input.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) {
                if (e.length != 1) return
                val offset = e.offset
                val typed = runCatching { e.document.getText(offset, 1) }.getOrNull() ?: return
                if (typed != MENTION) return
                if (!opensMention(e, offset)) return
                SwingUtilities.invokeLater { show(offset) }
            }

            override fun removeUpdate(e: DocumentEvent) = Unit
            override fun changedUpdate(e: DocumentEvent) = Unit
        })
    }

    private fun opensMention(e: DocumentEvent, offset: Int): Boolean {
        if (offset == 0) return true
        val before = runCatching { e.document.getText(offset - 1, 1) }.getOrNull() ?: return false
        return before.isBlank()
    }

    private fun show(offset: Int) {
        if (popup?.isVisible == true) return
        if (!input.isShowing) return

        val at = caretPoint(offset) ?: return

        val broken = AgentConfigurations.getInstance(project).agents().error
        if (broken != null) {
            JBPopupFactory.getInstance()
                .createMessage("$broken -- fix that section before handing work over.")
                .show(at)
            return
        }

        val agents = AgentCatalog.all(project)
        if (agents.isEmpty()) {
            JBPopupFactory.getInstance()
                .createMessage("No agents are defined. Add one at .agents/<name>/AGENT.md.")
                .show(at)
            return
        }

        val renderer = object : ColoredListCellRenderer<AgentDefinition>() {
            override fun customizeCellRenderer(
                list: JList<out AgentDefinition>,
                value: AgentDefinition,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                append("@${value.name}", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                append("  ${value.description}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                if (!value.isFromFile) append("  ${value.origin}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                toolTipText = "Tools: ${value.tools.describe()}"
            }
        }

        val created = JBPopupFactory.getInstance().createPopupChooserBuilder(agents)
            .setTitle("Hand Over To")
            .setMovable(false)
            .setResizable(false)
            .setRenderer(renderer)
            .setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            .setNamerForFiltering { "${it.name} ${it.description}" }
            .setItemChosenCallback { agent ->
                removeMention(offset)
                onChosen(agent)
            }
            .setAdText("Keep typing to filter, Enter hands over, Escape leaves the @ alone")
            .createPopup()

        popup = created
        created.show(at)
    }

    private fun caretPoint(offset: Int): RelativePoint? {
        val rectangle = runCatching { input.modelToView2D(offset) }.getOrNull() ?: return null
        return RelativePoint(
            input,
            Point(rectangle.x.toInt(), (rectangle.y + rectangle.height).toInt()),
        )
    }

    private fun removeMention(offset: Int) {
        runCatching {
            if (input.document.getText(offset, 1) == MENTION) input.document.remove(offset, 1)
        }
    }

    private companion object {
        const val MENTION = "@"
    }
}
