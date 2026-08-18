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
 * What the Javadoc sweep should take out, and from where.
 *
 * A dialog rather than a menu item that just runs: this deletes documentation across a whole project
 * in one press, so the least it can do is say so first and let the run be narrowed. The report-only
 * box is the way to find out how much would go without finding out the hard way.
 */
class RemoveJavadocDialog(project: Project, private val selection: List<VirtualFile>) : DialogWrapper(project) {

    private val everyComment = JBRadioButton("Every Javadoc comment", true)
    private val blankOnly = JBRadioButton("Only empty ones -- a stub with no text in it")

    private val wholeProject = JBRadioButton("The whole project", true)
    private val selectedFiles = JBRadioButton(selectionLabel(project))

    private val reportOnly = JBCheckBox("Report only -- count them, change nothing")

    /** Read after [showAndGet] returns true. */
    val onlyBlank: Boolean get() = blankOnly.isSelected
    val useSelection: Boolean get() = selectedFiles.isSelected
    val dryRun: Boolean get() = reportOnly.isSelected

    init {
        title = "Remove Javadoc Comments"
        setOKButtonText("Remove")
        reportOnly.addActionListener { setOKButtonText(if (reportOnly.isSelected) "Count" else "Remove") }
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            comment(
                "Removes comments written in the Javadoc form -- Javadoc in Java, KDoc in Kotlin, " +
                    "and the same syntax wherever else the IDE parses it. Line comments and plain " +
                    "block comments are left alone.",
            )
        }
        // buttonsGroup rather than group: the DSL keeps the exclusivity itself and refuses a radio
        // button placed outside one, since a ButtonGroup made by hand is not something it can see.
        buttonsGroup("Which comments") {
            row { cell(everyComment) }
            row { cell(blankOnly) }
        }
        if (selection.isNotEmpty()) {
            buttonsGroup("Where") {
                row { cell(wholeProject) }
                row { cell(selectedFiles) }
            }
        }
        row { cell(reportOnly) }
        row {
            comment(
                "The whole sweep is one change: Undo in any edited file takes all of it back, and " +
                    "nothing is written to disk until the files are saved.",
            )
        }
    }

    private fun selectionLabel(project: Project): String = when (selection.size) {
        1 -> "Just ${PsiTargets.relativePath(project, selection.first())}"
        else -> "The ${selection.size} selected files and folders"
    }
}
