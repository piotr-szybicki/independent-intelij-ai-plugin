package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.github.piotrszybicki.independentintelijaiplugin.skills.SkillCatalog
import com.github.piotrszybicki.independentintelijaiplugin.skills.SkillDefinition
import com.github.piotrszybicki.independentintelijaiplugin.skills.SkillRoot
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import javax.swing.JList
import javax.swing.JTextPane
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

/**
 * A / that starts a word in the composer, which switches a skill on for this chat -- the same
 * thing the checkbox in [com.github.piotrszybicki.independentintelijaiplugin.settings.ConversationToolsDialog]
 * does, reachable without leaving the keyboard. This makes `use /skill-name` work while leaving
 * slashes embedded in a path or word alone.
 */
internal class SkillMentionPopup(
    private val project: Project,
    private val input: JTextPane,
    private val active: () -> Set<String>,
    private val onChosen: (SkillDefinition) -> Unit,
) {
    private var popup: JBPopup? = null

    fun install() = MentionPopupSupport.install(input, MENTION, ::opensMention, ::show)

    private fun opensMention(
        e: DocumentEvent,
        offset: Int,
    ): Boolean {
        if (offset == 0) return true
        val previous = runCatching { e.document.getText(offset - 1, 1) }.getOrNull() ?: return false
        return previous.singleOrNull()?.isWhitespace() == true
    }

    private fun show(offset: Int) {
        if (popup?.isVisible == true) return
        if (!input.isShowing) return

        val at = MentionPopupSupport.caretPoint(input, offset) ?: return

        val skills =
            runCatching { SkillCatalog.enabledIn(SkillCatalog.scan(project).skills) }
                .getOrDefault(emptyList())
        if (skills.isEmpty()) {
            JBPopupFactory
                .getInstance()
                .createMessage(
                    "No skills were found. Add one at ${SkillRoot.DEFAULT_PATHS.lines().first()}/" +
                        "<name>/SKILL.md, or point the settings page at another directory.",
                ).show(at)
            return
        }

        val alreadyOn = active()

        val renderer =
            object : ColoredListCellRenderer<SkillDefinition>() {
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

        val created =
            JBPopupFactory
                .getInstance()
                .createPopupChooserBuilder(skills)
                .setTitle("Switch a Skill On for This Chat")
                .setMovable(false)
                .setResizable(false)
                .setRenderer(renderer)
                .setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
                .setNamerForFiltering { "${it.name} ${it.description}" }
                .setItemChosenCallback { skill ->
                    MentionPopupSupport.replaceMarker(input, MENTION, "/${skill.name}", offset)
                    onChosen(skill)
                }.setAdText("Keep typing to filter, Enter switches it on, Escape leaves the / alone")
                .createPopup()

        popup = created
        created.show(at)
    }

    private companion object {
        const val MENTION = "/"
    }
}
