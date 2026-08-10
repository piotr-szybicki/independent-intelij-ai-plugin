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

    /**
     * Monospace family for code spans, taken from the editor scheme so it matches the rest of the
     * IDE.
     *
     * Guarded, because reading it is the first thing that instantiates `EditorColorsManager`, and
     * that service reads the registry in its constructor: when a restored tool window builds its
     * panes during frame init, the registry is not loaded yet and the platform logs an error. The
     * AWT logical family is a fine stand-in for the rare pane built that early.
     */
    private fun codeFontName(): String =
        if (LoadingState.COMPONENTS_LOADED.isOccurred) {
            EditorColorsManager.getInstance().globalScheme.editorFontName
        } else {
            Font.MONOSPACED
        }

    private fun addStyles(sheet: StyleSheet) {
        val codeFont = codeFontName()
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
        sheet.addRule("blockquote { margin: ${JBUI.scale(4)}px 0; padding-left: ${JBUI.scale(8)}px; border-left: 2px solid $quoteBorder; }")
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
