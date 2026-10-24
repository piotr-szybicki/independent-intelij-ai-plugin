package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Node
import org.commonmark.parser.Parser
import org.commonmark.renderer.NodeRenderer
import org.commonmark.renderer.html.HtmlRenderer

internal class ShellCommandBlocks(
    markdown: String,
) {
    private val commands = mutableListOf<String>()

    val html: String =
        HtmlRenderer
            .builder()
            .escapeHtml(true)
            .nodeRendererFactory { context ->
                object : NodeRenderer {
                    override fun getNodeTypes(): Set<Class<out Node>> = setOf(FencedCodeBlock::class.java)

                    override fun render(node: Node) {
                        val block = node as FencedCodeBlock
                        val language =
                            block.info
                                .trim()
                                .substringBefore(' ')
                                .lowercase()
                        val writer = context.writer
                        val shell = language in SHELL_LANGUAGES && block.literal.isNotBlank()
                        if (shell) {
                            val index = commands.size
                            commands += block.literal.trimEnd('\r', '\n')
                            writer.raw(
                                """
                                <div class="shell-code-wrapper">
                                    <pre class="shell-code-block" id="shell-command-$index">
                                """.trimIndent(),
                            )
                        } else {
                            writer.tag("pre")
                        }
                        writer.tag(
                            "code",
                            if (language.isEmpty()) emptyMap() else mapOf("class" to "language-$language"),
                        )
                        writer.text(block.literal)
                        writer.tag("/code")
                        writer.tag("/pre")
                        if (shell) {
                            writer.raw("</div>")
                        }
                    }
                }
            }.build()
            .render(Parser.builder().build().parse(markdown))
            .trim()

    val contents: List<String> get() = commands

    private companion object {
        val SHELL_LANGUAGES =
            setOf("sh", "shell", "bash", "zsh", "fish", "powershell", "pwsh", "ps1", "cmd", "bat", "batch")
    }
}
