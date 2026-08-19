package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.JBColor
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LayoutManager
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.Style
import javax.swing.text.StyleConstants
import javax.swing.text.html.StyleSheet

internal object ChatColors {

    val background: Color get() = UIUtil.getPanelBackground()
    val foreground: Color get() = UIUtil.getLabelForeground()
    val muted: Color get() = UIUtil.getContextHelpForeground()
    val accent: Color get() = JBUI.CurrentTheme.Link.Foreground.ENABLED
    val separator: Color get() = JBColor.namedColor("Group.separatorColor", 0xEBECF0, 0x393B40)

    val error: Color get() = JBColor.namedColor("Component.errorFocusColor", 0xE53E4D, 0x8B3C3C)

    val warning: Color get() = JBColor.namedColor("Component.warningFocusColor", 0xE0A200, 0xA07800)

    val userBubble: Color get() = mix(background, accent, if (JBColor.isBright()) 0.10 else 0.22)
    val userBubbleBorder: Color get() = mix(background, accent, if (JBColor.isBright()) 0.28 else 0.38)

    val aiBubble: Color get() = mix(background, foreground, 0.03)
    val aiBubbleBorder: Color get() = mix(background, foreground, 0.16)

    val toolGroup: Color get() = mix(background, foreground, 0.04)
    val toolGroupBorder: Color get() = mix(background, foreground, 0.12)

    val card: Color get() = mix(background, foreground, 0.05)
    val cardHover: Color get() = mix(background, foreground, 0.10)
    val cardBorder: Color get() = mix(background, foreground, 0.14)

    val codeBackground: Color get() = mix(background, foreground, 0.07)

    fun mix(base: Color, tint: Color, tintRatio: Double): Color {
        fun channel(a: Int, b: Int) = (a * (1 - tintRatio) + b * tintRatio).toInt().coerceIn(0, 255)
        return Color(channel(base.red, tint.red), channel(base.green, tint.green), channel(base.blue, tint.blue))
    }

    fun hex(color: Color): String = String.format("#%02x%02x%02x", color.red, color.green, color.blue)
}

internal object ChatMetrics {
    val rowGap: Int get() = JBUI.scale(10)
    val bubblePadding: Int get() = JBUI.scale(9)
    val bubbleIndent: Int get() = JBUI.scale(28)
    val arc: Int get() = JBUI.scale(12)
    val smallArc: Int get() = JBUI.scale(8)
}

internal open class RoundedPanel(
    layout: LayoutManager? = null,
    private val arc: () -> Int = { ChatMetrics.arc },
    private val fill: () -> Color? = { null },
    private val stroke: () -> Color? = { null },
    private val strokeWidth: () -> Float = { 1f },
) : JPanel(layout) {

    init {
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arcSize = arc()
            fill()?.let {
                g2.color = it
                g2.fillRoundRect(0, 0, width, height, arcSize, arcSize)
            }
            stroke()?.let {
                g2.color = it
                val pen = strokeWidth()
                g2.stroke = BasicStroke(pen)
                val inset = pen / 2f
                g2.draw(
                    RoundRectangle2D.Float(
                        inset, inset, width - pen, height - pen, arcSize.toFloat(), arcSize.toFloat(),
                    )
                )
            }
        } finally {
            g2.dispose()
        }
    }
}

internal fun chatCodeFontName(): String {
    val colors = ApplicationManager.getApplication()?.getServiceIfCreated(EditorColorsManager::class.java)
        ?: return Font.MONOSPACED
    return colors.globalScheme.editorFontName
}

internal class HtmlTextPane : JEditorPane("text/html", "") {

    private var fixedWidth = -1

    init {
        isEditable = false
        isOpaque = false
        border = JBUI.Borders.empty()
        val kit = HTMLEditorKitBuilder().withWordWrapViewFactory().build()
        editorKit = kit
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        font = JBFont.label()
        foreground = ChatColors.foreground
        addStyles(kit.styleSheet)
    }

    private fun addStyles(sheet: StyleSheet) {
        val codeFont = chatCodeFontName()
        val codeBackground = ChatColors.hex(ChatColors.codeBackground)
        sheet.addRule("body { margin: 0; padding: 0; }")
        sheet.addRule("p { margin: 0 0 ${JBUI.scale(6)}px 0; }")
        sheet.addRule("ul, ol { margin: ${JBUI.scale(4)}px 0 ${JBUI.scale(6)}px ${JBUI.scale(18)}px; }")
        sheet.addRule("li { margin: ${JBUI.scale(2)}px 0; }")
        sheet.addRule("h1, h2, h3, h4 { margin: ${JBUI.scale(10)}px 0 ${JBUI.scale(4)}px 0; }")
        sheet.addRule("a { color: ${ChatColors.hex(ChatColors.accent)}; }")
        sheet.addRule("code { font-family: $codeFont; background-color: $codeBackground; }")
        sheet.addRule(
            "pre { font-family: $codeFont; background-color: $codeBackground; " +
                "margin: ${JBUI.scale(6)}px 0; padding: ${JBUI.scale(7)}px; }"
        )
        sheet.addRule("table { margin: ${JBUI.scale(6)}px 0; }")
        sheet.addRule("td, th { padding: ${JBUI.scale(2)}px ${JBUI.scale(6)}px; }")
    }

    fun setHtml(html: String) {
        text = "<html><body>$html</body></html>"
        reflow()
    }

    fun applyWidth(width: Int) {
        if (width <= 0 || width == fixedWidth) return
        fixedWidth = width
        reflow()
    }

    private fun reflow() {
        if (fixedWidth > 0) setSize(fixedWidth, Short.MAX_VALUE.toInt())
        revalidate()
    }

    override fun getPreferredSize(): Dimension {
        val preferred = super.getPreferredSize()
        return if (fixedWidth > 0) Dimension(fixedWidth, preferred.height) else preferred
    }

    override fun getMinimumSize(): Dimension = if (fixedWidth > 0) Dimension(0, preferredSize.height) else super.getMinimumSize()
}

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

    private val bodyStyle: Style = addStyle("chat-input-body", null).apply {
        StyleConstants.setFontFamily(this, JBFont.label().family)
        StyleConstants.setFontSize(this, JBFont.label().size)
        StyleConstants.setForeground(this, ChatColors.foreground)
    }

    private val codeStyle: Style = addStyle("chat-input-code", bodyStyle).apply {
        StyleConstants.setFontFamily(this, chatCodeFontName())
        StyleConstants.setBackground(this, ChatColors.codeBackground)
    }

    private val fenceStyle: Style = addStyle("chat-input-fence", bodyStyle).apply {
        StyleConstants.setForeground(this, ChatColors.mix(UIUtil.getTextFieldBackground(), ChatColors.foreground, 0.22))
        StyleConstants.setFontSize(this, maxOf(10, JBFont.small().size - 1))
    }

    private var restyling = false

    init {
        border = JBUI.Borders.empty()
        background = UIUtil.getTextFieldBackground()
        foreground = ChatColors.foreground
        font = JBFont.label()
        document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = scheduleRestyle()
            override fun removeUpdate(e: DocumentEvent) = scheduleRestyle()
            override fun changedUpdate(e: DocumentEvent) = scheduleRestyle()
        })
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
        val blocks = FENCED_BLOCK.findAll(text).map { match ->
            val content = match.groups[1]!!.range
            FencedBlock(
                fenceStart = match.range.first,
                contentStart = content.first,
                contentEnd = content.last + 1,
                fenceEnd = match.range.last + 1,
            )
        }.toList()

        styled.setCharacterAttributes(0, styled.length, bodyStyle, true)
        blocks.forEach { block ->
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
    }

    override fun getPreferredSize(): Dimension {
        val preferred = super.getPreferredSize()
        val textFont = font ?: return preferred
        val textMargin = margin ?: return preferred
        val rows = visibleRows * getFontMetrics(textFont).height +
            insets.top + insets.bottom + textMargin.top + textMargin.bottom
        return Dimension(preferred.width, maxOf(preferred.height, rows))
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        if (document.length > 0) return
        val textFont = font ?: return
        val textMargin = margin ?: return
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.color = ChatColors.muted
            g2.font = textFont
            g2.drawString(
                placeholder,
                insets.left + textMargin.left,
                insets.top + textMargin.top + g2.fontMetrics.ascent,
            )
        } finally {
            g2.dispose()
        }
    }

    private companion object {
        val FENCED_BLOCK = Regex("```[^\\r\\n]*\\R([\\s\\S]*?)\\R```")
    }
}