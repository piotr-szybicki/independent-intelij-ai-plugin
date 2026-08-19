package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal object WithheldOutput {

    private const val DIRECTORY = ".cache"

    private val LOG = Logger.getInstance(WithheldOutput::class.java)

    private val FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    fun save(project: Project, toolName: String, output: String): Path? {
        val root = project.basePath ?: return null
        val name = "${sanitize(toolName)}-${FILE_STAMP.format(LocalDateTime.now())}.txt"
        val path = Path.of(root, DIRECTORY, name)

        try {
            Files.createDirectories(path.parent)
            Files.writeString(path, output)
        } catch (e: Exception) {
            LOG.warn("Could not write the withheld output to $path", e)
            return null
        }
        return path
    }

    fun open(project: Project, path: Path): Boolean {
        // Resolving the file through the VFS fires creation events, which is a write -- and this is
        // called from a Swing callback, which holds no write-intent lock. Same bargain as
        // [TranscriptExport], down to the reason: see https://jb.gg/ij-platform-threading.
        val file = findFile(path)
        if (file == null) {
            LOG.warn("Wrote the withheld output to $path but could not find it in the VFS")
            return false
        }
        FileEditorManager.getInstance(project).openFile(file, true)
        return true
    }

    fun read(path: Path): String? {
        var text: String? = null
        WriteIntentReadAction.run {
            val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
            val documents = FileDocumentManager.getInstance()
            val document = file?.let { documents.getDocument(it) } ?: return@run
            // Saved as well as read, so what went to the model and what is on disk are the same
            // text -- the file is the record of what was sent, and one that disagreed with it would
            // be worse than no record at all.
            runCatching { documents.saveDocument(document) }
                .onFailure { LOG.warn("Could not save $path before sending it; sending the editor's copy", it) }
            text = document.text
        }
        return text ?: runCatching { Files.readString(path) }
            .onFailure { LOG.warn("Could not read the edited output from $path", it) }
            .getOrNull()
    }

    private fun findFile(path: Path): VirtualFile? {
        var file: VirtualFile? = null
        WriteIntentReadAction.run {
            file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
        }
        return file
    }

    private fun sanitize(toolName: String): String =
        toolName.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            .ifEmpty { "tool" }
            .take(60)
}
