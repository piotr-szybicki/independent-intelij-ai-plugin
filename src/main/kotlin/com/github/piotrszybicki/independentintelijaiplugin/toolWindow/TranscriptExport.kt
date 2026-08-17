package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Writes one reply out to a `.md` file.
 *
 * Markdown rather than the HTML the transcript draws, because markdown is what the model wrote in
 * the first place: exporting the rendering would mean converting a rendering back, and every code
 * fence and table in the answer is a chance for that to lose something.
 */
internal object TranscriptExport {

    private const val NOTIFICATION_GROUP = "AICodingAgent.Export"
    private val LOG = Logger.getInstance(TranscriptExport::class.java)

    private val FILE_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")
    private val HEADER_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /**
     * Asks where to put [markdown] and writes it there. EDT only -- it opens a dialog.
     *
     * Cancelling the dialog is a no-op. A reply with nothing in it still writes a file: the header
     * line makes it a record that there was a turn and it said nothing, which is more use than a
     * button that silently does nothing when pressed.
     */
    fun save(project: Project, markdown: String) {
        val now = LocalDateTime.now()
        val descriptor = FileSaverDescriptor(
            "Export Reply to Markdown",
            "Save this reply as a Markdown file",
            "md",
        )
        // Defaulted to the project root: it is the folder the answer is about, and the dialog
        // remembers wherever the user moves to from there.
        val target = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
            .save(project.basePath?.let { Path.of(it) }, "ai-reply-${FILE_STAMP.format(now)}.md")
            ?: return

        val file = target.file
        try {
            file.writeText(document(markdown, now))
        } catch (e: IOException) {
            LOG.warn("Could not export the reply to ${file.path}", e)
            notify("The reply could not be exported", "${file.path}\n\n${e.message}", NotificationType.ERROR, project, null)
            return
        }

        // Asynchronously, because this is the EDT: without it a file written inside the project
        // stays invisible in the project view until something else happens to refresh it.
        VfsUtil.markDirtyAndRefresh(true, false, false, file)
        notify("Reply exported", file.path, NotificationType.INFORMATION, project, file)
    }

    /**
     * The reply, under a line saying where it came from and when.
     *
     * A stamped line rather than nothing at all, because the point of the file is being read later,
     * and by then which chat produced it is not something the reader still has.
     */
    private fun document(markdown: String, now: LocalDateTime): String =
        "*Exported from AICodingAgent on ${HEADER_STAMP.format(now)}*\n\n${markdown.trim()}\n"

    /** @param exported the file to offer to open, or null when the export did not get that far */
    private fun notify(title: String, content: String, type: NotificationType, project: Project, exported: File?) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(title, content, type)
        if (exported != null) {
            notification.addAction(
                NotificationAction.createSimpleExpiring("Open") {
                    // Looked up when the action is used rather than when the balloon is built: the
                    // refresh above is asynchronous, so the file may not be in the VFS yet.
                    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(exported.toPath())
                        ?.let { FileEditorManager.getInstance(project).openFile(it, true) }
                }
            )
        }
        notification.notify(project)
    }
}
