package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.InplaceButton
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants

internal class ChatAttachments(
    private val project: Project,
    private val onStatus: (String) -> Unit,
    private val onRevalidate: () -> Unit,
) {
    data class PendingAttachment(val summary: String, val body: String)

    private val maxAttachmentChars = 60_000
    private val maxTotalAttachmentChars = 150_000

    private val pending = mutableListOf<PendingAttachment>()

    private val attachmentList = JPanel(GridLayout(0, 1, 0, JBUI.scale(3))).apply { isOpaque = false }

    val component: JComponent = CappedScrollPane(attachmentList, 96).apply {
        border = JBUI.Borders.empty()
        isOpaque = false
        viewport.isOpaque = false
        isVisible = false
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
    }

    fun attachments(): List<PendingAttachment> = pending.toList()

    fun attachFromEditor() {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        if (editor == null) {
            onStatus("Open a file in the editor to attach it.")
            return
        }

        val selectionModel = editor.selectionModel
        val selectedText = selectionModel.selectedText
        val document = editor.document
        val virtualFile = FileDocumentManager.getInstance().getFile(document)
        val displayPath = displayPathOf(virtualFile)
        val extension = virtualFile?.extension.orEmpty()

        if (selectedText.isNullOrEmpty()) {
            val text = document.text
            if (text.isBlank()) {
                onStatus("$displayPath is empty.")
                return
            }
            if (text.length > maxAttachmentChars) {
                onStatus(
                    "$displayPath is too large to attach (${text.length} characters). " +
                        "Select the part you mean, or just ask -- the AI can read it itself."
                )
                return
            }
            add(
                body = fence("Full contents of $displayPath (${document.lineCount} lines)", extension, text),
                summary = "$displayPath (whole file)",
            )
            return
        }

        val startLine = document.getLineNumber(selectionModel.selectionStart) + 1
        val endLine = document.getLineNumber(selectionModel.selectionEnd) + 1
        val lineRange = if (startLine == endLine) "line $startLine" else "lines $startLine-$endLine"

        add(
            body = fence("Selected code from $displayPath ($lineRange)", extension, selectedText),
            summary = "$displayPath ($lineRange)",
        )
    }

    fun startSideChat(code: String, displayPath: String, extension: String, lineRange: String) {
        add(
            body = fence("Selected code from $displayPath ($lineRange)", extension, code),
            summary = "$displayPath ($lineRange)",
        )
    }

    fun add(body: String, summary: String) {
        val existing = pending.indexOfFirst { it.summary == summary }
        val othersLength = pending
            .filterIndexed { index, _ -> index != existing }
            .sumOf { it.body.length }
        if (othersLength + body.length > maxTotalAttachmentChars) {
            onStatus(
                "That is more than $maxTotalAttachmentChars characters of attachments. " +
                    "Send what is attached, or remove some of it, first."
            )
            return
        }

        val attachment = PendingAttachment(summary, body)
        if (existing >= 0) pending[existing] = attachment else pending.add(attachment)
        onStatus(" ")
        refresh()
    }

    fun clear() {
        pending.clear()
        refresh()
    }

    private fun remove(attachment: PendingAttachment) {
        pending.remove(attachment)
        refresh()
    }

    private fun refresh() {
        attachmentList.removeAll()
        pending.forEach { attachmentList.add(chip(it)) }
        component.isVisible = pending.isNotEmpty()
        onRevalidate()
    }

    private fun chip(attachment: PendingAttachment): JComponent {
        val chip = RoundedPanel(
            BorderLayout(JBUI.scale(6), 0),
            arc = { ChatMetrics.smallArc },
            fill = { ChatColors.card },
            stroke = { ChatColors.separator },
        ).apply {
            border = JBUI.Borders.empty(JBUI.scale(3), JBUI.scale(7))
            add(
                JBLabel("📎 ${attachment.summary}").apply {
                    font = JBFont.small()
                    foreground = ChatColors.foreground
                    toolTipText = attachment.summary
                },
                BorderLayout.CENTER,
            )
            add(
                InplaceButton("Remove attachment", AllIcons.Actions.Close) { remove(attachment) },
                BorderLayout.EAST,
            )
        }
        return JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(chip)
        }
    }

    private fun displayPathOf(virtualFile: VirtualFile?): String {
        val basePath = project.basePath
        return when {
            virtualFile == null -> "untitled"
            basePath != null && virtualFile.path.startsWith(basePath) ->
                virtualFile.path.removePrefix(basePath).trimStart('/', '\\')
            else -> virtualFile.path
        }
    }

    private fun fence(heading: String, extension: String, code: String): String = buildString {
        append(heading).append(":\n")
        append("```").append(extension).append('\n')
        append(code)
        if (!code.endsWith("\n")) append('\n')
        append("```")
    }
}
