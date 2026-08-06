package org.jetbrains.plugins.template.toolWindow

import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

object MarkdownRenderer {

    private val parser: Parser = Parser.builder().build()

    // escapeHtml(true) so literal '<'/'>' typed outside of code spans (e.g. "List<String>") render
    // as text rather than being interpreted as markup.
    private val renderer: HtmlRenderer = HtmlRenderer.builder().escapeHtml(true).build()

    fun toHtml(markdown: String): String = renderer.render(parser.parse(markdown)).trim()
}
