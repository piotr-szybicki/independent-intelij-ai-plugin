package com.github.piotrszybicki.independentintelijaiplugin.cleanup

import com.github.piotrszybicki.independentintelijaiplugin.logging.CommentDatabase
import com.github.piotrszybicki.independentintelijaiplugin.tools.PsiTargets
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

class CommentDatabaseDialog(
    project: Project,
    private val selection: List<VirtualFile>,
    private val mode: Mode,
) : DialogWrapper(project) {

    enum class Mode(val title: String, val okText: String) {
        STORE("Move Javadoc Comments to Database", "Move"),
        RESTORE("Restore Comments From Database", "Restore"),
    }

    private val wholeProject = JBRadioButton("The whole project", true)
    private val selectedFiles = JBRadioButton(selectionLabel(project))

    private val reportOnly = JBCheckBox("Report only -- count them, change nothing")

    val useSelection: Boolean get() = selectedFiles.isSelected
    val dryRun: Boolean get() = reportOnly.isSelected

    init {
        title = mode.title
        setOKButtonText(mode.okText)
        reportOnly.addActionListener { setOKButtonText(if (reportOnly.isSelected) "Count" else mode.okText) }
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row { comment(explanation()) }
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

    private fun explanation(): String = when (mode) {
        Mode.STORE ->
            "Every Javadoc-form comment is written to the <code>${CommentDatabase.TABLE}</code> table " +
                "and replaced in the code with <code>// comment_id: N</code>. After this the " +
                "documentation exists only in that database: if it is dropped or unreachable, the " +
                "code keeps nothing but the ids. Line comments are left alone."

        Mode.RESTORE ->
            "Every <code>// comment_id: N</code> marker is replaced with the comment stored under " +
                "that id. A marker whose row is missing is left where it is. The rows are not " +
                "deleted, so this can be run again."
    }

    private fun selectionLabel(project: Project): String = when (selection.size) {
        1 -> "Just ${PsiTargets.relativePath(project, selection.first())}"
        else -> "The ${selection.size} selected files and folders"
    }
}
