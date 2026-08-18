package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.github.piotrszybicki.independentintelijaiplugin.changes.ChangeSessionService
import com.github.piotrszybicki.independentintelijaiplugin.tools.PsiTargets
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants

internal class ChangesBar(
    private val project: Project,
    private val session: ChangeSessionService,
    private val onStatus: (String) -> Unit,
    private val onRevalidate: () -> Unit,
) {
    private val changesLabel = JBLabel().apply {
        font = JBFont.small()
        foreground = ChatColors.foreground
    }
    private val approveButton = JButton("Approve").apply { toolTipText = "Keep all changes the AI made" }
    private val revertButton = JButton("Revert").apply { toolTipText = "Restore all touched files" }

    private val changedFilesList = JPanel(GridLayout(0, 1, 0, JBUI.scale(1))).apply { isOpaque = false }

    private val changedFilesScroll = CappedScrollPane(changedFilesList, 132).apply {
        border = JBUI.Borders.emptyTop(JBUI.scale(4))
        isOpaque = false
        viewport.isOpaque = false
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
    }

    val component: JComponent = RoundedPanel(
        BorderLayout(),
        arc = { ChatMetrics.smallArc },
        fill = { ChatColors.card },
        stroke = { ChatColors.separator },
    ).apply {
        isVisible = false
        border = JBUI.Borders.empty(JBUI.scale(5), JBUI.scale(9))
        add(
            JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                isOpaque = false
                add(changesLabel, BorderLayout.CENTER)
                add(
                    JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(5), 0)).apply {
                        isOpaque = false
                        add(approveButton)
                        add(revertButton)
                    },
                    BorderLayout.EAST,
                )
            },
            BorderLayout.NORTH,
        )
        add(changedFilesScroll, BorderLayout.CENTER)
    }

    init {
        approveButton.addActionListener { approveChanges() }
        revertButton.addActionListener { revertChanges() }
    }

    fun update(count: Int) {
        component.isVisible = count > 0
        changesLabel.text = if (count == 1) "1 file changed" else "$count files changed"
        approveButton.isEnabled = count > 0
        revertButton.isEnabled = count > 0

        changedFilesList.removeAll()
        if (count > 0) session.changedFiles().forEach { changedFilesList.add(changedFileRow(it)) }
        onRevalidate()
    }

    private fun changedFileRow(file: VirtualFile): JComponent {
        var hovered = false
        val row = RoundedPanel(
            BorderLayout(JBUI.scale(6), 0),
            arc = { ChatMetrics.smallArc },
            fill = { if (hovered) ChatColors.cardHover else null },
        ).apply {
            border = JBUI.Borders.empty(JBUI.scale(2), JBUI.scale(4))
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Open ${PsiTargets.relativePath(project, file)}"
        }

        row.add(
            JBLabel(file.name, file.fileType.icon, SwingConstants.LEFT).apply { font = JBFont.small() },
            BorderLayout.WEST,
        )
        val folder = PsiTargets.relativePath(project, file).substringBeforeLast('/', "")
        if (folder.isNotEmpty()) {
            row.add(
                JBLabel(folder).apply {
                    font = JBFont.small()
                    foreground = ChatColors.muted
                },
                BorderLayout.CENTER,
            )
        }

        row.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = openChangedFile(file)
            override fun mouseEntered(e: MouseEvent) {
                hovered = true
                row.repaint()
            }

            override fun mouseExited(e: MouseEvent) {
                hovered = false
                row.repaint()
            }
        })
        return row
    }

    private fun openChangedFile(file: VirtualFile) {
        if (!file.isValid) {
            onStatus("${file.name} is no longer there.")
            return
        }
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    private fun approveChanges() {
        session.approveAll()
    }

    private fun revertChanges() {
        val paths = session.changedPaths()
        if (paths.isEmpty()) return
        if (!ChatDialogs.confirmRevert(project, paths)) return

        val failed = session.revertAll()
        if (failed.isNotEmpty()) {
            ChatDialogs.showRevertFailure(project, failed)
        }
    }
}
