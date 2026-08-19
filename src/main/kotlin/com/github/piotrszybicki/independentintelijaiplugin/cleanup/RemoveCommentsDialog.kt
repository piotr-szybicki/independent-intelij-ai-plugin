package com.github.piotrszybicki.independentintelijaiplugin.cleanup

import com.github.piotrszybicki.independentintelijaiplugin.tools.PsiTargets
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/**
 * What a removal sweep should take out, and from where.
 *
 * A dialog rather than a menu item that just runs: this deletes comments across a whole project in
 * one press, so the least it can do is say so first and let the run be narrowed. The report-only box
 * is the way to find out how much would go without finding out the hard way.
 */
class RemoveCommentsDialog(project: Project, private val selection: List<VirtualFile>) : DialogWrapper(project) {

    private val javadoc = JBRadioButton("Javadoc comments", true)
    private val blankJavadoc = JBRadioButton("Only empty Javadoc -- a stub with no text in it")
    private val line = JBRadioButton("Line comments (//)")
    private val both = JBRadioButton("Javadoc and line comments")

    /**
     * Off by default, and enabled only while a choice that could reach a marker is selected.
     *
     * A `// comment_id: N` line is the only pointer to a comment in the database. Removing it does
     * not delete a line of text so much as strand the paragraph it stood for, so it takes a deliberate
     * tick rather than coming along with "remove the line comments".
     */
    private val markers = JBCheckBox("Include // comment_id markers -- the stored comments become unreachable")

    private val wholeProject = JBRadioButton("The whole project", true)
    private val selectedFiles = JBRadioButton(selectionLabel(project))

    private val reportOnly = JBCheckBox("Report only -- count them, change nothing")

    /** Read after [showAndGet] returns true. */
    val choice: CommentChoice
        get() = when {
            blankJavadoc.isSelected -> CommentChoice.EMPTY_JAVADOC
            line.isSelected -> CommentChoice.LINE
            both.isSelected -> CommentChoice.JAVADOC_AND_LINE
            else -> CommentChoice.JAVADOC
        }

    /** Gated on the choice as well as the box, so a tick left over from an earlier choice cannot act. */
    val includeMarkers: Boolean
        get() = markers.isSelected && touchesLineComments()

    val useSelection: Boolean get() = selectedFiles.isSelected
    val dryRun: Boolean get() = reportOnly.isSelected

    init {
        title = "Remove Comments"
        setOKButtonText("Remove")
        listOf(javadoc, blankJavadoc, line, both).forEach { it.addActionListener { syncMarkers() } }
        reportOnly.addActionListener { setOKButtonText(if (reportOnly.isSelected) "Count" else "Remove") }
        init()
        syncMarkers()
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            comment(
                "Javadoc means the form rather than the language -- Javadoc in Java, KDoc in Kotlin, " +
                    "and the same syntax wherever else the IDE parses it. Plain block comments " +
                    "(<code>/* ... */</code>) are always left alone.",
            )
        }
        buttonsGroup("Which comments") {
            row { cell(javadoc) }
            row { cell(blankJavadoc) }
            row { cell(line) }
            row { cell(both) }
        }
        row { cell(markers) }
        if (selection.isNotEmpty()) {
            buttonsGroup("Where") {
                row { cell(wholeProject) }
                row { cell(selectedFiles) }
            }
        }
        row { cell(reportOnly) }
        row {
            comment(
                "The whole sweep is one change: Undo in any edited file takes all of it back. The " +
                    "files it changes are saved when it finishes.",
            )
        }
    }

    private fun touchesLineComments(): Boolean = line.isSelected || both.isSelected

    private fun syncMarkers() {
        markers.isEnabled = touchesLineComments()
    }

    private fun selectionLabel(project: Project): String = when (selection.size) {
        1 -> "Just ${PsiTargets.relativePath(project, selection.first())}"
        else -> "The ${selection.size} selected files and folders"
    }
}
