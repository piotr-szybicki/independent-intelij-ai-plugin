package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JTextPane
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.Style
import javax.swing.text.StyleConstants

internal class ChatInputPane(
    private val placeholder: String,
    private val visibleRows: Int = 3,
) : JTextPane() {
    private data class FencedBlock(
        val fenceStart: Int,
        val contentStart: Int,
        val contentEnd: Int,
        val fenceEnd: Int,
    )

    private val bodyStyle: Style =
        addStyle("chat-input-body", null).apply {
            StyleConstants.setFontFamily(this, JBFont.label().family)
            StyleConstants.setFontSize(this, JBFont.label().size)
            StyleConstants.setForeground(this, ChatColors.foreground)
        }

    private val codeStyle: Style =
        addStyle("chat-input-code", bodyStyle).apply {
            StyleConstants.setFontFamily(this, chatCodeFontName())
            StyleConstants.setBackground(this, ChatColors.codeBackground)
        }

    private val fenceStyle: Style =
        addStyle("chat-input-fence", bodyStyle).apply {
            StyleConstants.setForeground(
                this,
                ChatColors.mix(UIUtil.getTextFieldBackground(), ChatColors.foreground, FENCE_COLOR_RATIO),
            )
            StyleConstants.setFontSize(this, maxOf(MINIMUM_FONT_SIZE, JBFont.small().size - 1))
        }

    private var restyling = false

    init {
        border = JBUI.Borders.empty()
        background = UIUtil.getTextFieldBackground()
        foreground = ChatColors.foreground
        font = JBFont.label()
        document.addDocumentListener(
            object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = scheduleRestyle()

                override fun removeUpdate(e: DocumentEvent) = scheduleRestyle()

                override fun changedUpdate(e: DocumentEvent) = scheduleRestyle()
            },
        )
    }

    private fun scheduleRestyle() {
        if (restyling) return
        SwingUtilities.invokeLater {
            restyling = true
            try {
                restyle()
            } finally {
                restyling = false
            }
        }
    }

    private fun restyle() {
        val styled = styledDocument
        val text = styled.getText(0, styled.length)
        val blocks =
            FENCED_BLOCK
                .findAll(text)
                .mapNotNull { match ->
                    val content = match.groups[1]?.range ?: return@mapNotNull null
                    FencedBlock(
                        fenceStart = match.range.first,
                        contentStart = content.first,
                        contentEnd = content.last + 1,
                        fenceEnd = match.range.last + 1,
                    )
                }.toList()

        styled.setCharacterAttributes(0, styled.length, bodyStyle, true)
        blocks.forEach { block -> applyBlockStyle(styled, block) }
    }

    private fun applyBlockStyle(
        styled: javax.swing.text.StyledDocument,
        block: FencedBlock,
    ) {
        val openingFenceLength = (block.contentStart - block.fenceStart).coerceAtLeast(0)
        if (openingFenceLength > 0) {
            styled.setCharacterAttributes(block.fenceStart, openingFenceLength, fenceStyle, true)
        }
        val contentLength = (block.contentEnd - block.contentStart).coerceAtLeast(0)
        if (contentLength > 0) {
            styled.setCharacterAttributes(block.contentStart, contentLength, codeStyle, true)
        }
        val closingFenceLength = (block.fenceEnd - block.contentEnd).coerceAtLeast(0)
        if (closingFenceLength > 0) {
            styled.setCharacterAttributes(block.contentEnd, closingFenceLength, fenceStyle, true)
        }
    }

    override fun getPreferredSize(): Dimension {
        val preferred = super.getPreferredSize()
        val textFont = font ?: return preferred
        val textMargin = margin ?: return preferred
        val rows =
            visibleRows * getFontMetrics(textFont).height +
                insets.top + insets.bottom + textMargin.top + textMargin.bottom
        return Dimension(preferred.width, maxOf(preferred.height, rows))
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        if (document.length > 0) return
        val textFont = font ?: return
        val textMargin = margin ?: return
        val graphics = g.create() as Graphics2D
        try {
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            graphics.color = ChatColors.muted
            graphics.font = textFont
            graphics.drawString(
                placeholder,
                insets.left + textMargin.left,
                insets.top + textMargin.top + graphics.fontMetrics.ascent,
            )
        } finally {
            graphics.dispose()
        }
    }

    private companion object {
        const val FENCE_COLOR_RATIO = 0.22
        const val MINIMUM_FONT_SIZE = 10
        val FENCED_BLOCK = Regex("```[^\\r\\n]*\\R([\\s\\S]*?)\\R```")
    }
}
