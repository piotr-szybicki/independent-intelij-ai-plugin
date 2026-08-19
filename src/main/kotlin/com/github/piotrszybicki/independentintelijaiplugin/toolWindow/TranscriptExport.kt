package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.WriteIntentReadAction
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

internal object TranscriptExport {

    private const val NOTIFICATION_GROUP = "AICodingAgent.Export"
    private val LOG = Logger.getInstance(TranscriptExport::class.java)

    private val FILE_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")
    private val HEADER_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun save(project: Project, markdown: String) {
        val now = LocalDateTime.now()
        val descriptor = FileSaverDescriptor(
            "Export Reply to Markdown",
            "Save this reply as a Markdown file",
            "md",
        )
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

        WriteIntentReadAction.run { VfsUtil.markDirtyAndRefresh(true, false, false, file) }
        notify("Reply exported", file.path, NotificationType.INFORMATION, project, file)
    }

    private fun document(markdown: String, now: LocalDateTime): String =
        "*Exported from AICodingAgent on ${HEADER_STAMP.format(now)}*\n\n${markdown.trim()}\n"

    private fun notify(title: String, content: String, type: NotificationType, project: Project, exported: File?) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(title, content, type)
        if (exported != null) {
            notification.addAction(
                NotificationAction.createSimpleExpiring("Open") {
                    WriteIntentReadAction.run {
                        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(exported.toPath())
                            ?.let { FileEditorManager.getInstance(project).openFile(it, true) }
                    }
                }
            )
        }
        notification.notify(project)
    }
}
