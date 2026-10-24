package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.github.piotrszybicki.independentintelijaiplugin.files.EditorBackedFileReader
import com.github.piotrszybicki.independentintelijaiplugin.tools.ChatWorkingDirectory
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.diagnostic.Logger
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

    fun save(
        project: Project,
        toolName: String,
        output: String,
    ): Path? {
        val root = ChatWorkingDirectory.path(project) ?: return null
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

    fun open(
        project: Project,
        path: Path,
    ): Boolean {
        val file = findFile(path)
        if (file == null) {
            LOG.warn("Wrote the withheld output to $path but could not find it in the VFS")
            return false
        }
        FileEditorManager.getInstance(project).openFile(file, true)
        return true
    }

    fun read(path: Path): String? =
        EditorBackedFileReader.read(
            path,
            LOG,
            "Could not save $path before sending it; sending the editor's copy",
            "Could not read the edited output from $path",
        )

    private fun findFile(path: Path): VirtualFile? {
        var file: VirtualFile? = null
        WriteIntentReadAction.run {
            file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
        }
        return file
    }

    private fun sanitize(toolName: String): String =
        toolName
            .filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            .ifEmpty { "tool" }
            .take(60)
}
