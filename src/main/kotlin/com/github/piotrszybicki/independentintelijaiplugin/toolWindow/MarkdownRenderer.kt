package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

object MarkdownRenderer {
    internal val extensions = listOf(TablesExtension.create())
    private val parser: Parser = Parser.builder().extensions(extensions).build()

    private val renderer: HtmlRenderer =
        HtmlRenderer
            .builder()
            .extensions(extensions)
            .escapeHtml(true)
            .build()

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
