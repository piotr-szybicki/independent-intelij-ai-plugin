package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.github.piotrszybicki.independentintelijaiplugin.agents.AgentCatalog
import com.github.piotrszybicki.independentintelijaiplugin.agents.AgentDefinition
import com.github.piotrszybicki.independentintelijaiplugin.settings.AgentConfiguration
import com.github.piotrszybicki.independentintelijaiplugin.settings.AgentConfigurations
import com.github.piotrszybicki.independentintelijaiplugin.settings.AgentRosterConfig
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import javax.swing.JList
import javax.swing.JTextPane
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

internal class AgentMentionPopup(
    private val project: Project,
    private val input: JTextPane,
    private val onChosen: (AgentDefinition) -> Unit,
) {
    private var popup: JBPopup? = null

    fun install() = MentionPopupSupport.install(input, MENTION, ::opensMention, ::show)

    private fun opensMention(
        e: DocumentEvent,
        offset: Int,
    ): Boolean {
        if (offset == 0) return true
        val before = runCatching { e.document.getText(offset - 1, 1) }.getOrNull() ?: return false
        return before.isBlank()
    }

    private fun show(offset: Int) {
        if (popup?.isVisible == true) return
        if (!input.isShowing) return

        val at = MentionPopupSupport.caretPoint(input, offset) ?: return

        val broken = AgentConfigurations.getInstance(project).agents().error
        if (broken != null) {
            JBPopupFactory
                .getInstance()
                .createMessage("$broken -- fix that section before handing work over.")
                .show(at)
            return
        }

        val agents = AgentCatalog.all(project)
        if (agents.isEmpty()) {
            JBPopupFactory
                .getInstance()
                .createMessage(
                    "No agents are defined. Write one into the \"${AgentRosterConfig.SECTION}\" section " +
                        "of ${AgentConfiguration.FILE_NAME}, or as .agents/<name>/AGENT.md.",
                ).show(at)
            return
        }

        val renderer =
            object : ColoredListCellRenderer<AgentDefinition>() {
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

        val created =
            JBPopupFactory
                .getInstance()
                .createPopupChooserBuilder(agents)
                .setTitle("Hand Over To")
                .setMovable(false)
                .setResizable(false)
                .setRenderer(renderer)
                .setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
                .setNamerForFiltering { "${it.name} ${it.description}" }
                .setItemChosenCallback { agent ->
                    MentionPopupSupport.removeMarker(input, MENTION, offset)
                    onChosen(agent)
                }.setAdText("Keep typing to filter, Enter hands over, Escape leaves the @ alone")
                .createPopup()

        popup = created
        created.show(at)
    }

    private companion object {
        const val MENTION = "@"
    }
}
