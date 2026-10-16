package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import java.awt.BorderLayout

internal class AssistantRow(
    private val markdown: String,
) : ChatRow(BorderLayout()) {
    private val body = HtmlTextPane()

    init {
        body.setHtml(MarkdownRenderer.toHtml(markdown))
        add(body, BorderLayout.CENTER)
    }

    override fun applyAvailableWidth(width: Int) = body.applyWidth(width)

    override fun toMarkdown(): String? = markdown.trim().takeIf { it.isNotEmpty() }
}
