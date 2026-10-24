package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import javax.swing.ScrollPaneConstants

internal class AssistantRow(
    private val markdown: String,
    onCopyCode: (String) -> Unit = { CopyPasteManager.getInstance().setContents(StringSelection(it)) },
    onPasteCommand: (String) -> Unit = {},
) : ChatRow(BorderLayout()) {
    private val body = HtmlTextPane(allowHorizontalOverflow = true)
    private val scrollPane =
        JBScrollPane(body).apply {
            border = JBUI.Borders.empty()
            viewportBorder = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }

    init {
        val blocks = ShellCommandBlocks(markdown)
        body.setHtml(blocks.html)
        val controls = ShellCodeControls(body, blocks.contents, onCopyCode, onPasteCommand)
        body.layoutControls = controls::layout
        scrollPane.viewport.addChangeListener { controls.layout() }
        add(scrollPane, BorderLayout.CENTER)
    }

    override fun applyAvailableWidth(width: Int) {
        body.applyWidth(width)
        // Reserve scrollbar height in the transcript's vertical layout when content cannot wrap.
        scrollPane.horizontalScrollBarPolicy =
            if (body.preferredSize.width > width) {
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS
            } else {
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            }
        revalidate()
    }

    override fun toMarkdown(): String? = markdown.trim().takeIf { it.isNotEmpty() }
}
