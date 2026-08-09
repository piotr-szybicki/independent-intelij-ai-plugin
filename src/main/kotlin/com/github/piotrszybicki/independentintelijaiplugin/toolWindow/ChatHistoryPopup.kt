package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.github.piotrszybicki.independentintelijaiplugin.history.ChatHistoryService
import com.github.piotrszybicki.independentintelijaiplugin.history.ChatSummary
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.EmptyIcon
import java.awt.event.ActionListener
import java.awt.event.KeyEvent
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel

/** The saved-conversations list behind the tool window's history button. */
internal object ChatHistoryPopup {

    /**
     * @param currentId the chat on screen, marked in the list and the one [onCurrentDeleted] is for
     * @param onOpen given the id of the chat to switch to
     * @param onCurrentDeleted the open chat was deleted, so the window has nothing left to show
     */
    fun show(
        service: ChatHistoryService,
        chats: List<ChatSummary>,
        currentId: String,
        dataContext: DataContext,
        onOpen: (String) -> Unit,
        onCurrentDeleted: () -> Unit,
    ) {
        if (chats.isEmpty()) {
            JBPopupFactory.getInstance()
                .createMessage("No saved chats yet — this one is saved as soon as you send a message.")
                .showInBestPositionFor(dataContext)
            return
        }

        val model = DefaultListModel<ChatSummary>().apply { chats.forEach { addElement(it) } }
        val list = JBList(model).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = object : ColoredListCellRenderer<ChatSummary>() {
                override fun customizeCellRenderer(
                    list: JList<out ChatSummary>,
                    value: ChatSummary,
                    index: Int,
                    selected: Boolean,
                    hasFocus: Boolean,
                ) {
                    icon = if (value.id == currentId) AllIcons.Actions.Forward else EmptyIcon.ICON_16
                    append(value.title)
                    append("  ${DateFormatUtil.formatPrettyDateTime(value.updatedAt)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
            chats.firstOrNull { it.id == currentId }?.let { setSelectedValue(it, true) }
        }

        val popup = JBPopupFactory.getInstance().createPopupChooserBuilder(list)
            .setTitle("Chat History")
            .setMovable(true)
            .setResizable(true)
            .setNamerForFiltering { it.title }
            .setItemChosenCallback { onOpen(it.id) }
            .setAdText("Enter opens a chat, Delete removes it")
            .createPopup()

        list.registerKeyboardAction(
            ActionListener {
                val selected = list.selectedValue
                if (selected != null) {
                    service.delete(selected.id)
                    model.removeElement(selected)
                    if (selected.id == currentId) onCurrentDeleted()
                    if (model.isEmpty) popup.cancel()
                }
            },
            KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0),
            JComponent.WHEN_FOCUSED,
        )

        popup.showInBestPositionFor(dataContext)
    }
}
