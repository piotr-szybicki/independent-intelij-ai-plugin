package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBUI
import java.awt.Rectangle
import java.awt.Shape
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.SwingConstants
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.View
import javax.swing.text.html.HTML
import javax.swing.text.html.HTMLDocument

internal class ShellCodeControls(
    private val pane: HtmlTextPane,
    commands: List<String>,
    onCopy: (String) -> Unit,
    onPaste: (String) -> Unit,
) {
    private val buttons =
        commands.map { command ->
            listOf(
                button(AllIcons.Actions.Copy, "Copy code") { onCopy(command) },
                button(
                    IconUtil.scale(IconLoader.getIcon("/icons/terminal-code.png", javaClass), null, 0.25f),
                    "Paste to terminal",
                ) { onPaste(command) },
            )
        }

    init {
        pane.layout = null
        buttons.flatten().forEach(pane::add)
    }

    fun layout() {
        if (buttons.isEmpty() || pane.width <= 0) return
        val root = pane.getUI().getRootView(pane)
        root.setSize(pane.width.toFloat(), pane.height.toFloat())
        val blocks = mutableMapOf<Int, Rectangle>()
        locate(root, Rectangle(0, 0, pane.width, pane.height), blocks)
        val size = JBUI.scale(24)
        val gap = JBUI.scale(4)
        buttons.forEachIndexed { index, controls ->
            val block = blocks[index]
            controls.forEach { it.isVisible = block != null }
            if (block != null) {
                val right = minOf(block.x + block.width, pane.visibleRect.maxX.toInt()) - JBUI.scale(6)
                controls.forEachIndexed { position, button ->
                    button.setBounds(right - 2 * size - gap + position * (size + gap), block.y - size / 2, size, size)
                }
            }
        }
    }

    private fun locate(
        view: View,
        allocation: Shape,
        blocks: MutableMap<Int, Rectangle>,
    ) {
        val id =
            view.element.attributes
                .getAttribute(HTML.Attribute.ID)
                ?.toString()
                .orEmpty()
        if (view.element.name == "pre" && id.startsWith("shell-command-")) {
            val sheet = (pane.document as HTMLDocument).styleSheet
            // The view allocation includes outer margins; its painted box starts inside them.
            val withoutMargins = SimpleAttributeSet(view.attributes)
            withoutMargins.addAttributes(sheet.getDeclaration("margin: 0;"))
            val fullInset = sheet.getBoxPainter(view.attributes).getInset(SwingConstants.TOP, view)
            val innerInset = sheet.getBoxPainter(withoutMargins).getInset(SwingConstants.TOP, view)
            val topMargin = (fullInset - innerInset).toInt()
            val border = allocation.bounds.apply { y += topMargin }
            id.removePrefix("shell-command-").toIntOrNull()?.let { blocks.putIfAbsent(it, border) }
            return
        }
        for (index in 0 until view.viewCount) {
            val child = view.getChildAllocation(index, allocation) ?: continue
            locate(view.getView(index), child, blocks)
        }
    }

    private fun button(
        icon: Icon,
        tooltip: String,
        action: () -> Unit,
    ): JButton = CodeActionButton(icon, tooltip, action)
}
