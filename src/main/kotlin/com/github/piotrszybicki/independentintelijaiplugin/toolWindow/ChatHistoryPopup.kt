package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.github.piotrszybicki.independentintelijaiplugin.history.ChatHistoryService
import com.github.piotrszybicki.independentintelijaiplugin.history.ChatSummary
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.InplaceButton
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.EmptyIcon
import java.awt.event.ActionListener
import java.awt.event.KeyEvent
import javax.swing.JList
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel

internal object ChatHistoryPopup {

    fun show(
        project: Project,
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

        val renderer = object : ColoredListCellRenderer<ChatSummary>() {
            override fun customizeCellRenderer(
                list: JList<out ChatSummary>,
                value: ChatSummary,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                icon = if (value.id == currentId) AllIcons.Actions.Forward else EmptyIcon.ICON_16
                value.agentName?.let {
                    append("@$it  ", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, ChatColors.accent))
                }
                append(value.title)
                append(
                    "  ${DateFormatUtil.formatPrettyDateTime(value.updatedAt)}",
                    SimpleTextAttributes.GRAYED_ATTRIBUTES,
                )
            }
        }

        var selected: ChatSummary? = chats.firstOrNull { it.id == currentId }
        var popup: JBPopup? = null

        val builder = JBPopupFactory.getInstance().createPopupChooserBuilder(chats)
            .setTitle("Chat History")
            .setMovable(true)
            .setResizable(true)
            .setRenderer(renderer)
            .setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            .setNamerForFiltering { listOfNotNull(it.agentName, it.title).joinToString(" ") }
            .setItemSelectedCallback { selected = it }
            .setItemChosenCallback { onOpen(it.id) }
            .setSettingButton(
                InplaceButton("Delete All Chats", AllIcons.Actions.GC) {
                    popup?.cancel()
                    if (confirmDeleteAll(project, chats.size)) {
                        service.deleteAll()
                        onCurrentDeleted()
                    }
                },
            )
            .setAdText("Enter opens a chat, Delete removes it")
            .registerKeyboardAction(
                KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0),
            ) {
                val victim = selected
                if (victim != null) {
                    service.delete(victim.id)
                    if (victim.id == currentId) onCurrentDeleted()
                    popup?.cancel()
                }
            }

        selected?.let { builder.setSelectedValue(it, true) }

        val created = builder.createPopup()
        popup = created
        created.showInBestPositionFor(dataContext)
    }

    private fun confirmDeleteAll(project: Project, count: Int): Boolean =
        Messages.showYesNoDialog(
            project,
            "Delete all $count saved ${if (count == 1) "chat" else "chats"}? This cannot be undone.",
            "Delete Chat History",
            Messages.getWarningIcon(),
        ) == Messages.YES
}
