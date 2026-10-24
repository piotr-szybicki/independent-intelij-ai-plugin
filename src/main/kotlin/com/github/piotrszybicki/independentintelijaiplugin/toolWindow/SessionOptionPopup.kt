package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import javax.swing.JList
import javax.swing.JTextPane
import javax.swing.ListSelectionModel

internal class SessionOptionPopup(
    private val input: JTextPane,
    private val onChosen: (SessionOption) -> Unit,
) {
    private var popup: JBPopup? = null

    fun install() =
        MentionPopupSupport.install(input, "+", { event, offset ->
            offset == 0 || event.document.getText(offset - 1, 1).isBlank()
        }, ::show)

    private fun show(offset: Int) {
        if (popup?.isVisible == true || !input.isShowing) return
        val at = MentionPopupSupport.caretPoint(input, offset) ?: return
        val renderer =
            object : ColoredListCellRenderer<SessionOption>() {
                override fun customizeCellRenderer(
                    list: JList<out SessionOption>,
                    value: SessionOption,
                    index: Int,
                    selected: Boolean,
                    hasFocus: Boolean,
                ) {
                    append("+${value.command}", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("  ${value.description}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
        val created =
            JBPopupFactory
                .getInstance()
                .createPopupChooserBuilder(SessionOption.entries.toList())
                .setTitle("Session Options")
                .setMovable(false)
                .setResizable(false)
                .setRenderer(renderer)
                .setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
                .setNamerForFiltering { "${it.command} ${it.description}" }
                .setItemChosenCallback { option ->
                    ComposerSubmitSuppression.suppressNextSubmit(input)
                    MentionPopupSupport.removeMarker(input, "+", offset)
                    onChosen(option)
                }.setAdText("Keep typing to filter, Enter applies, Escape leaves the + alone")
                .createPopup()
        popup = created
        created.show(at)
    }
}
