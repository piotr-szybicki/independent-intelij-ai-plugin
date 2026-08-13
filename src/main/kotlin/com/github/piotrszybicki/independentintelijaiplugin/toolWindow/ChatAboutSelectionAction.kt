package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.IconLoader

/**
 * The "Open a Side Chat" button in the editor's floating code toolbar.
 *
 * When the user selects code and this icon appears in the floating toolbar, clicking it saves the
 * current conversation, starts a fresh chat in the AICodingAgent tool window, and attaches the
 * selection as context. The user can then immediately type a question — "explain this", "refactor
 * this", "is there a bug here" — in a dedicated chat that is about just this code.
 *
 * The previous conversation is preserved in history and can be reopened from there.
 */
class ChatAboutSelectionAction : AnAction(
    "Open a Side Chat",
    "Start a new AI chat about the selected code",
    IconLoader.getIcon("/icons/chatAboutCode.svg", ChatAboutSelectionAction::class.java),
) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor?.selectionModel?.hasSelection() == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val selectionModel = editor.selectionModel
        val selectedText = selectionModel.selectedText
        if (selectedText.isNullOrEmpty()) return

        val document = editor.document
        val virtualFile = FileDocumentManager.getInstance().getFile(document)
        val basePath = project.basePath

        val displayPath = when {
            virtualFile == null -> "untitled"
            basePath != null && virtualFile.path.startsWith(basePath) ->
                virtualFile.path.removePrefix(basePath).trimStart('/', '\\')
            else -> virtualFile.path
        }
        val extension = virtualFile?.extension.orEmpty()

        val startLine = document.getLineNumber(selectionModel.selectionStart) + 1
        val endLine = document.getLineNumber(selectionModel.selectionEnd) + 1
        val lineRange = if (startLine == endLine) "line $startLine" else "lines $startLine-$endLine"

        ChatToolWindowFactory.openSideChat(project, selectedText, displayPath, extension, lineRange)
    }
}