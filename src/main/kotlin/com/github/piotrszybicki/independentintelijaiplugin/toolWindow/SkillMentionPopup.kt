package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.github.piotrszybicki.independentintelijaiplugin.skills.SkillCatalog
import com.github.piotrszybicki.independentintelijaiplugin.skills.SkillDefinition
import com.github.piotrszybicki.independentintelijaiplugin.skills.SkillRoot
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

/**
 * The / at the start of a message, which switches a skill on for this chat -- the same thing the
 * checkbox in [com.github.piotrszybicki.independentintelijaiplugin.settings.ConversationToolsDialog]
 * does, reachable without leaving the keyboard. Only the first non-blank character of the message
 * opens it, so a path typed mid-sentence is left alone.
 */
internal class SkillMentionPopup(
    private val project: Project,
    private val input: JTextPane,
    private val active: () -> Set<String>,
    private val onChosen: (SkillDefinition) -> Unit,
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
        val before = runCatching { e.document.getText(0, offset) }.getOrNull() ?: return false
        return before.isBlank()
    }

    private fun show(offset: Int) {
        if (popup?.isVisible == true) return
        if (!input.isShowing) return

        val at = caretPoint(offset) ?: return

        val skills = runCatching { SkillCatalog.enabledIn(SkillCatalog.scan(project).skills) }
            .getOrDefault(emptyList())
        if (skills.isEmpty()) {
            JBPopupFactory.getInstance()
                .createMessage(
                    "No skills were found. Add one at ${SkillRoot.DEFAULT_PATHS.lines().first()}/" +
                        "<name>/SKILL.md, or point the settings page at another directory.",
                )
                .show(at)
            return
        }

        val alreadyOn = active()

        val renderer = object : ColoredListCellRenderer<SkillDefinition>() {
            override fun customizeCellRenderer(
                list: JList<out SkillDefinition>,
                value: SkillDefinition,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                append("/${value.name}", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                if (value.name in alreadyOn) {
                    append("  already on", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
                append("  ${value.description}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                toolTipText = value.description
            }
        }

        val created = JBPopupFactory.getInstance().createPopupChooserBuilder(skills)
            .setTitle("Switch a Skill On for This Chat")
            .setMovable(false)
            .setResizable(false)
            .setRenderer(renderer)
            .setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            .setNamerForFiltering { "${it.name} ${it.description}" }
            .setItemChosenCallback { skill ->
                removeMention(offset)
                onChosen(skill)
            }
            .setAdText("Keep typing to filter, Enter switches it on, Escape leaves the / alone")
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
        const val MENTION = "/"
    }
}
