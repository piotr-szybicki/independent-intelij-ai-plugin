package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

object MarkdownRenderer {

    private val parser: Parser = Parser.builder().build()

    // escapeHtml(true) so literal '<'/'>' typed outside of code spans (e.g. "List<String>") render
    // as text rather than being interpreted as markup.
    private val renderer: HtmlRenderer = HtmlRenderer.builder().escapeHtml(true).build()

    fun toHtml(markdown: String): String = renderer.render(parser.parse(markdown)).trim()

    fun normalizeQuoteFences(markdown: String): String {
        if (!markdown.contains("'''")) return markdown
        var inBacktickFence = false
        var inQuoteFence = false
        return markdown.lines().joinToString("\n") { line ->
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("```") && !inQuoteFence -> {
                    inBacktickFence = !inBacktickFence
                    line
                }

                trimmed.startsWith("'''") && !inBacktickFence -> {
                    inQuoteFence = !inQuoteFence
                    line.replaceFirst("'''", "```")
                }

                else -> line
            }
        }
    }
}
