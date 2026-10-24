package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.Font
import javax.swing.JEditorPane
import javax.swing.text.View
import javax.swing.text.html.StyleSheet

internal fun chatCodeFontName(): String {
    val colors =
        ApplicationManager.getApplication()?.getServiceIfCreated(EditorColorsManager::class.java)
            ?: return Font.MONOSPACED
    return colors.globalScheme.editorFontName
}

internal class HtmlTextPane(
    private val allowHorizontalOverflow: Boolean = false,
) : JEditorPane("text/html", "") {
    private var fixedWidth = -1
    var layoutControls: (() -> Unit)? = null

    override fun doLayout() {
        super.doLayout()
        layoutControls?.invoke()
    }

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
        val codeBorder = "1px solid ${ChatColors.hex(ChatColors.aiBubbleBorder)}"
        sheet.addRule("body { margin: 0; padding: 0; }")
        sheet.addRule("p { margin: 0 0 ${JBUI.scale(6)}px 0; }")
        sheet.addRule("ul, ol { margin: ${JBUI.scale(4)}px 0 ${JBUI.scale(6)}px ${JBUI.scale(18)}px; }")
        sheet.addRule("li { margin: ${JBUI.scale(2)}px 0; }")
        sheet.addRule("h1, h2, h3, h4 { margin: ${JBUI.scale(10)}px 0 ${JBUI.scale(4)}px 0; }")
        sheet.addRule("a { color: ${ChatColors.hex(ChatColors.accent)}; }")
        sheet.addRule("div.shell-code-wrapper { margin: ${JBUI.scale(14)}px 0 ${JBUI.scale(6)}px; }")
        sheet.addRule(
            "pre.shell-code-block { margin: 0; border: $codeBorder; " +
                "padding: ${JBUI.scale(14)}px ${JBUI.scale(7)}px ${JBUI.scale(7)}px; }",
        )
        sheet.addRule("code { font-family: $codeFont; background-color: $codeBackground; }")
        sheet.addRule(
            "pre { font-family: $codeFont; background-color: $codeBackground; " +
                "margin: ${JBUI.scale(6)}px 0; padding: ${JBUI.scale(7)}px; }",
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
        val width = if (allowHorizontalOverflow) maxOf(fixedWidth, contentMinimumWidth()) else fixedWidth
        return if (fixedWidth > 0) Dimension(width, preferred.height) else preferred
    }

    override fun getScrollableTracksViewportWidth(): Boolean =
        if (allowHorizontalOverflow) {
            maxOf(fixedWidth, contentMinimumWidth()) <= (parent?.width ?: 0)
        } else {
            super.getScrollableTracksViewportWidth()
        }

    private fun contentMinimumWidth(): Int =
        kotlin.math.ceil(getUI().getRootView(this).getMinimumSpan(View.X_AXIS).toDouble()).toInt() +
            insets.left + insets.right

    override fun getMinimumSize(): Dimension =
        if (fixedWidth > 0) {
            Dimension(0, preferredSize.height)
        } else {
            super.getMinimumSize()
        }
}
