package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

object MarkdownRenderer {

    private val parser: Parser = Parser.builder().build()

    // escapeHtml(true) so literal '<'/'>' typed outside of code spans (e.g. "List<String>") render
    // as text rather than being interpreted as markup.
    private val renderer: HtmlRenderer = HtmlRenderer.builder().escapeHtml(true).build()

    fun toHtml(markdown: String): String = renderer.render(parser.parse(markdown)).trim()

    /**
     * Rewrites a line of `'''` into a ``` code fence. Markdown only knows backtick and tilde
     * fences, but triple quotes are the easier thing to type, so questions written that way should
     * still come out as code. Lines inside a backtick fence are left alone, so triple quotes that
     * are part of the code itself (a Python docstring, say) survive untouched.
     */
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
