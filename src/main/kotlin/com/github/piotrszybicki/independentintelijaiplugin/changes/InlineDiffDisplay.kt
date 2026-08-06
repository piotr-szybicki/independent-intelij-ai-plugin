package com.github.piotrszybicki.independentintelijaiplugin.changes

import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diff.DiffColors
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.MarkupModel
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.DumbProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import java.awt.Color
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Point
import java.awt.Rectangle

/**
 * Paints the session's changes the way a diff does: the current lines get a full-width green
 * background, and above each change sits a band carrying the lines it replaced in red, plus Accept
 * and Reject buttons for that one change.
 *
 * The removed text no longer exists in the document, so it cannot be highlighted -- it is rendered
 * as a block inlay instead, which is also what gives the buttons somewhere to live. Every hunk gets
 * a band, including pure insertions, so there is always something to click.
 *
 * Colors come from [DiffColors] via the active editor scheme, so this follows the user's theme
 * instead of hardcoding green and red.
 */
class InlineDiffDisplay(
    private val project: Project,
    private val onAccept: (VirtualFile, Hunk) -> Unit,
    private val onReject: (VirtualFile, Hunk) -> Unit,
) : ChangeDisplay, Disposable {

    private val log = Logger.getInstance(InlineDiffDisplay::class.java)
    private val markup = mutableMapOf<VirtualFile, FileMarkup>()

    private class FileMarkup(
        val markupModel: MarkupModel,
        val highlighters: List<RangeHighlighter>,
        val inlays: List<Inlay<*>>,
    )

    /** The editor whose cursor we last overrode, so it can be handed back on the way out. */
    private var cursorOwner: EditorEx? = null

    init {
        // One listener for every editor, rather than one per inlay: the renderer under the pointer
        // carries the file and hunk it belongs to, so there is nothing to look up.
        val multicaster = EditorFactory.getInstance().eventMulticaster
        multicaster.addEditorMouseListener(
            object : EditorMouseListener {
                override fun mouseClicked(event: EditorMouseEvent) = handleClick(event)
            },
            this,
        )
        // EditorCustomElementRenderer has no cursor hook, so hovering has to be tracked separately
        // and pushed onto the editor by hand.
        multicaster.addEditorMouseMotionListener(
            object : EditorMouseMotionListener {
                override fun mouseMoved(event: EditorMouseEvent) = handleMove(event)
            },
            this,
        )
    }

    /** The button under [point], if the pointer is over one at all. */
    private fun actionAt(editor: Editor, point: Point): Pair<HunkBandRenderer, HunkBandRenderer.Action>? {
        val inlay = editor.inlayModel.getElementAt(point) ?: return null
        val renderer = inlay.renderer as? HunkBandRenderer ?: return null
        val bounds = inlay.bounds ?: return null
        val action = renderer.hitTest(point.x - bounds.x, point.y - bounds.y) ?: return null
        return renderer to action
    }

    private fun handleMove(event: EditorMouseEvent) {
        val editor = event.editor as? EditorEx ?: return
        if (editor.project != project) return

        val overButton = event.area == EditorMouseEventArea.EDITING_AREA &&
            actionAt(editor, event.mouseEvent.point) != null

        if (overButton) {
            // Plain arrow, so the band stops reading as editable text.
            editor.setCustomCursor(this, Cursor.getDefaultCursor())
            cursorOwner = editor
        } else if (cursorOwner === editor) {
            releaseCursor()
        }
    }

    private fun releaseCursor() {
        cursorOwner?.setCustomCursor(this, null)
        cursorOwner = null
    }

    private fun handleClick(event: EditorMouseEvent) {
        if (event.isConsumed || event.area != EditorMouseEventArea.EDITING_AREA) return
        val editor = event.editor
        if (editor.project != project) return

        val (renderer, action) = actionAt(editor, event.mouseEvent.point) ?: return
        event.consume()

        // Both actions redraw, which disposes the very inlay this click came from. Deferring keeps
        // that out of the middle of mouse-event dispatch.
        val file = renderer.file
        val hunk = renderer.hunk
        ApplicationManager.getApplication().invokeLater({
            when (action) {
                HunkBandRenderer.Action.ACCEPT -> onAccept(file, hunk)
                HunkBandRenderer.Action.REJECT -> onReject(file, hunk)
            }
        }, project.disposed)
    }

    override fun show(file: VirtualFile, baseline: String) {
        clear(file)

        val document = FileDocumentManager.getInstance().getDocument(file) ?: return
        val current = document.immutableCharSequence

        val fragments = try {
            ComparisonManager.getInstance()
                .compareLines(baseline, current, ComparisonPolicy.DEFAULT, DumbProgressIndicator.INSTANCE)
        } catch (e: Exception) {
            // DiffTooBigException for very large files; the gutter stripes still work.
            log.info("Could not diff ${file.path} against its baseline: ${e.message}")
            return
        }
        if (fragments.isEmpty()) return

        val baselineLines = baseline.split("\n")
        val markupModel = DocumentMarkupModel.forDocument(document, project, true)
        val editors = EditorFactory.getInstance().getEditors(document, project)

        val highlighters = mutableListOf<RangeHighlighter>()
        val inlays = mutableListOf<Inlay<*>>()

        for (fragment in fragments) {
            val hunk = Hunk(fragment.startLine1, fragment.endLine1, fragment.startLine2, fragment.endLine2)

            // Surviving lines: a full-width band across every line the hunk now occupies.
            // DIFF_INSERTED (green) is used for replaced lines too, not just brand new ones, so the
            // pairing reads as a unified diff: red band above for what went, green for what landed.
            if (hunk.newStart < hunk.newEnd && hunk.newStart < document.lineCount) {
                val lastLine = (hunk.newEnd - 1).coerceAtMost(document.lineCount - 1)
                highlighters.add(
                    markupModel.addRangeHighlighter(
                        DiffColors.DIFF_INSERTED,
                        document.getLineStartOffset(hunk.newStart),
                        document.getLineEndOffset(lastLine),
                        HighlighterLayer.ADDITIONAL_SYNTAX,
                        HighlighterTargetArea.LINES_IN_RANGE,
                    )
                )
            }

            val removed = if (hunk.oldStart < hunk.oldEnd && hunk.oldStart < baselineLines.size) {
                baselineLines.subList(hunk.oldStart, hunk.oldEnd.coerceAtMost(baselineLines.size))
            } else {
                emptyList()
            }

            val anchorLine = hunk.newStart.coerceIn(0, (document.lineCount - 1).coerceAtLeast(0))
            val anchorOffset = document.getLineStartOffset(anchorLine)
            for (editor in editors) {
                val inlay = editor.inlayModel.addBlockElement(
                    anchorOffset,
                    /* relatesToPrecedingText = */ false,
                    /* showAbove = */ true,
                    /* priority = */ 0,
                    HunkBandRenderer(file, hunk, removed),
                )
                if (inlay != null) inlays.add(inlay)
            }
        }

        markup[file] = FileMarkup(markupModel, highlighters, inlays)
    }

    override fun clear(file: VirtualFile) {
        val existing = markup.remove(file) ?: return
        existing.highlighters.forEach { runCatching { existing.markupModel.removeHighlighter(it) } }
        existing.inlays.forEach { runCatching { Disposer.dispose(it) } }
        // The band under the pointer may have just been disposed; without this the arrow sticks
        // until the mouse moves again.
        releaseCursor()
    }

    override fun clearAll() {
        markup.keys.toList().forEach { clear(it) }
        releaseCursor()
    }

    override fun dispose() {
        clearAll()
    }

    /**
     * Draws one hunk's band: a row of Accept/Reject buttons, then the lines this hunk replaced on
     * the deleted-diff background, in the editor's own font so they line up with the real code.
     *
     * The button rectangles are recorded during painting, in coordinates relative to the band, and
     * [hitTest] reads them back. Painting is the only place the metrics are known, so a band that
     * has never been shown on screen simply has nothing to hit.
     */
    private class HunkBandRenderer(
        val file: VirtualFile,
        val hunk: Hunk,
        private val removedLines: List<String>,
    ) : EditorCustomElementRenderer {

        enum class Action { ACCEPT, REJECT }

        private var acceptBounds: Rectangle? = null
        private var rejectBounds: Rectangle? = null

        fun hitTest(x: Int, y: Int): Action? = when {
            acceptBounds?.contains(x, y) == true -> Action.ACCEPT
            rejectBounds?.contains(x, y) == true -> Action.REJECT
            else -> null
        }

        override fun calcWidthInPixels(inlay: Inlay<*>): Int {
            val editor = inlay.editor
            val metrics = editor.contentComponent.getFontMetrics(editor.colorsScheme.getFont(EditorFontType.PLAIN))
            val textWidth = removedLines.maxOfOrNull { metrics.stringWidth(expandTabs(it, editor)) } ?: 0
            val buttonsWidth = metrics.stringWidth(ACCEPT_LABEL) + metrics.stringWidth(REJECT_LABEL) + PADDING * 6
            return maxOf(textWidth, buttonsWidth)
        }

        // One extra line above the removed text for the buttons, so a pure insertion -- which has no
        // removed text at all -- still gets a band to click on.
        override fun calcHeightInPixels(inlay: Inlay<*>): Int = inlay.editor.lineHeight * (removedLines.size + 1)

        override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
            val editor = inlay.editor
            val scheme = editor.colorsScheme
            val deleted = scheme.getAttributes(DiffColors.DIFF_DELETED)
            val lineHeight = editor.lineHeight
            val ascent = editor.ascent

            g.font = scheme.getFont(EditorFontType.PLAIN)
            val metrics = g.fontMetrics

            // Span the full editor width so the band reads as a line, not as a box around the text.
            val width = maxOf(targetRegion.width, editor.contentComponent.width)

            if (removedLines.isNotEmpty()) {
                deleted?.backgroundColor?.let { background ->
                    g.color = background
                    g.fillRect(targetRegion.x, targetRegion.y + lineHeight, width, lineHeight * removedLines.size)
                }
                g.color = deleted?.foregroundColor ?: scheme.defaultForeground
                for ((index, line) in removedLines.withIndex()) {
                    val baselineY = targetRegion.y + (index + 1) * lineHeight + ascent
                    g.drawString(expandTabs(line, editor), targetRegion.x, baselineY)
                }
            }

            val foreground = scheme.defaultForeground
            val accept = drawButton(g, metrics, ACCEPT_LABEL, targetRegion.x + PADDING, targetRegion.y, lineHeight, ascent, foreground)
            val reject = drawButton(g, metrics, REJECT_LABEL, accept.x + accept.width + PADDING, targetRegion.y, lineHeight, ascent, foreground)

            // Stored relative to the band, because that is what hitTest gets handed.
            acceptBounds = accept.translated(-targetRegion.x, -targetRegion.y)
            rejectBounds = reject.translated(-targetRegion.x, -targetRegion.y)
        }

        private fun drawButton(
            g: Graphics,
            metrics: java.awt.FontMetrics,
            label: String,
            x: Int,
            y: Int,
            lineHeight: Int,
            ascent: Int,
            foreground: Color,
        ): Rectangle {
            val width = metrics.stringWidth(label) + PADDING * 2
            val height = lineHeight - 2
            g.color = foreground
            g.drawRect(x, y + 1, width, height - 2)
            g.drawString(label, x + PADDING, y + ascent)
            return Rectangle(x, y + 1, width, height - 2)
        }

        private fun Rectangle.translated(dx: Int, dy: Int) = Rectangle(this.x + dx, this.y + dy, width, height)

        private fun expandTabs(line: String, editor: Editor): String {
            if (!line.contains('\t')) return line
            val tabSize = editor.settings.getTabSize(editor.project)
            return line.replace("\t", " ".repeat(tabSize.coerceAtLeast(1)))
        }

        private companion object {
            const val ACCEPT_LABEL = "Accept"
            const val REJECT_LABEL = "Reject"
            const val PADDING = 6
        }
    }
}
