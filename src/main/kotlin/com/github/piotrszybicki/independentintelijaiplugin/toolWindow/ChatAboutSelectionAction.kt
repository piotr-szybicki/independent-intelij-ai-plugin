package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.IconLoader
import com.github.piotrszybicki.independentintelijaiplugin.settings.AgentConfigurations

class ChatAboutSelectionAction : AnAction(
    "Open a Side Chat",
    "Start a new AI chat about the selected code",
    IconLoader.getIcon("/icons/chatAboutCode.svg", ChatAboutSelectionAction::class.java),
) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val project = e.project
        // Hidden outright when the plugin will not run, rather than a button that opens a tool
        // window explaining itself: this one sits in the editor's floating toolbar next to actions
        // that all do something -- see AgentConfigurations.unavailableReason.
        val available = project != null &&
            AgentConfigurations.getInstance(project).unavailableReason == null
        e.presentation.isEnabledAndVisible = available && editor?.selectionModel?.hasSelection() == true
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