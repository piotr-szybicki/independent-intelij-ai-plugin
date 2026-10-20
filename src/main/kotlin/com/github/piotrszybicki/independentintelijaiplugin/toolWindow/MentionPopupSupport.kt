package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.intellij.ui.awt.RelativePoint
import java.awt.Point
import javax.swing.JTextPane
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

internal object MentionPopupSupport {
    fun install(
        input: JTextPane,
        marker: String,
        opensMention: (DocumentEvent, Int) -> Boolean,
        show: (Int) -> Unit,
    ) {
        input.document.addDocumentListener(
            object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) {
                    if (e.length != 1) return
                    val offset = e.offset
                    val typed = runCatching { e.document.getText(offset, 1) }.getOrNull() ?: return
                    if (typed != marker || !opensMention(e, offset)) return
                    SwingUtilities.invokeLater { show(offset) }
                }

                override fun removeUpdate(e: DocumentEvent) = Unit

                override fun changedUpdate(e: DocumentEvent) = Unit
            },
        )
    }

    fun caretPoint(
        input: JTextPane,
        offset: Int,
    ): RelativePoint? {
        val rectangle = runCatching { input.modelToView2D(offset) }.getOrNull() ?: return null
        return RelativePoint(
            input,
            Point(rectangle.x.toInt(), (rectangle.y + rectangle.height).toInt()),
        )
    }

    fun removeMarker(
        input: JTextPane,
        marker: String,
        offset: Int,
    ) {
        runCatching {
            if (input.document.getText(offset, 1) == marker) input.document.remove(offset, 1)
        }
    }

}
