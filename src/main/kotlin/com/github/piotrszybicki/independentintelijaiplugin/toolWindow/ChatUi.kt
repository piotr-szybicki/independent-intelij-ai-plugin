package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.intellij.diagnostic.LoadingState
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.JBColor
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LayoutManager
import java.awt.RenderingHints
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.Style
import javax.swing.text.StyleConstants
import javax.swing.text.html.StyleSheet

/**
 * Palette for the chat tool window. Every value is derived from the active theme on read, so the
 * chat keeps looking native in light themes, dark themes and custom ones alike.
 */
internal object ChatColors {

    val background: Color get() = UIUtil.getPanelBackground()
    val foreground: Color get() = UIUtil.getLabelForeground()
    val muted: Color get() = UIUtil.getContextHelpForeground()
    val accent: Color get() = JBUI.CurrentTheme.Link.Foreground.ENABLED
    val separator: Color get() = JBColor.namedColor("Group.separatorColor", 0xEBECF0, 0x393B40)

    /** Bubble behind the user's own messages: the theme background nudged towards the accent hue. */
    val userBubble: Color get() = mix(background, accent, if (JBColor.isBright()) 0.10 else 0.22)
    val userBubbleBorder: Color get() = mix(background, accent, if (JBColor.isBright()) 0.28 else 0.38)

    /**
     * Bubble behind an AI turn. Deliberately neutral -- the accent is what marks a message as the
     * user's, and tinting both sides with it would take that away -- and fainter than [card], so the
     * tool cards sitting inside a turn still read as raised off it.
     */
    val aiBubble: Color get() = mix(background, foreground, 0.03)
    val aiBubbleBorder: Color get() = mix(background, foreground, 0.16)

    /** Neutral card, used for tool calls and the pending-changes bar. */
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

/** Scaled spacing used across the chat, kept in one place so the layout stays visually consistent. */
internal object ChatMetrics {
    val rowGap: Int get() = JBUI.scale(10)
    val bubblePadding: Int get() = JBUI.scale(9)
    /** How far a bubble is held off the far edge, so the two speakers read as opposite sides. */
    val bubbleIndent: Int get() = JBUI.scale(28)
    val arc: Int get() = JBUI.scale(12)
    val smallArc: Int get() = JBUI.scale(8)
}

/**
 * Panel with an antialiased rounded background. Colors are passed as lambdas rather than values so a
 * panel repaints itself correctly after a theme switch or a hover change.
 */
internal open class RoundedPanel(
    layout: LayoutManager? = null,
    private val arc: () -> Int = { ChatMetrics.arc },
    private val fill: () -> Color? = { null },
    private val stroke: () -> Color? = { null },
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
                g2.drawRoundRect(0, 0, width - 1, height - 1, arcSize, arcSize)
            }
        } finally {
            g2.dispose()
        }
    }
}

/**
 * Monospace family for code, taken from the editor scheme so it matches the rest of the IDE.
 *
 * Guarded, because reading it is the first thing that instantiates `EditorColorsManager`, and that
 * service reads the registry in its constructor: when a restored tool window builds its panes during
 * frame init, the registry is not loaded yet and the platform logs an error. The AWT logical family
 * is a fine stand-in for the rare pane built that early.
 */
internal fun chatCodeFontName(): String =
    if (LoadingState.COMPONENTS_LOADED.isOccurred) {
        EditorColorsManager.getInstance().globalScheme.editorFontName
    } else {
        Font.MONOSPACED
    }

/**
 * Read-only HTML pane sized for a vertical stack: [applyWidth] pins it to the width the transcript
 * can give it, and the preferred height then follows from how the text wraps at that width. Without
 * this, a `JEditorPane` inside a vertically stacked container reports the height of a single long
 * line and the message gets clipped.
 */
internal class HtmlTextPane : JEditorPane("text/html", "") {

    private var fixedWidth = -1

    init {
        isEditable = false
        isOpaque = false
        border = JBUI.Borders.empty()
        // withWordWrapViewFactory keeps long unbreakable tokens (paths, minified output) from
        // pushing a horizontal scrollbar into the transcript.
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
        val quoteBorder = ChatColors.hex(ChatColors.mix(ChatColors.background, ChatColors.foreground, 0.25))
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

    /** Pins the pane to [width] px so wrapped text reports the right height to the layout. */
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

/**
 * The composer's text field. A styled pane rather than a text area, so fenced blocks can be
 * rendered in a markdown-like code style while keeping the raw markdown text intact.
 */
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

    /** Set while [restyle] runs, because the attribute changes it makes come back as edits. */
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

    /**
     * Swing forbids touching a document from inside its own change notification, so the restyle waits
     * for the next EDT pass; [restyling] then swallows the notifications that restyle itself fires,
     * which would otherwise schedule another pass forever.
     */
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

    /** Fenced blocks in monospace with code background, fences de-emphasized. */
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

    /** Kept at least [visibleRows] tall, so an empty composer is the box it used to be. */
    override fun getPreferredSize(): Dimension {
        val preferred = super.getPreferredSize()
        // The look and feel installs both, and the layout can ask for a size before it has.
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
        /** Closed fenced blocks only: an unterminated one is still being typed. */
        val FENCED_BLOCK = Regex("```[^\\r\\n]*\\R([\\s\\S]*?)\\R```")
    }
}