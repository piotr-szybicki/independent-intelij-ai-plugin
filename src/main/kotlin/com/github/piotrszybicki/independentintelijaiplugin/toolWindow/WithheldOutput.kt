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

/**
 * The scratch file behind the Continue button: tool output that was too large to send, put somewhere
 * the user can cut it down to the part that mattered and hand it back.
 *
 * A file in the editor rather than a text area in a dialog, because the thing being edited is the
 * output of `find_in_files` or `git_diff` -- thousands of lines that want folding, searching and
 * multiple cursors, all of which the IDE already has and no dialog is going to grow.
 *
 * `.cache/` in the project root rather than a temp directory: it wants to be somewhere the user can
 * find it again, and somewhere already understood to be droppable. Nothing here ever deletes one --
 * the file outlives the turn on purpose, since the whole point is that it can be reopened, and a
 * directory of them is a directory the user can empty whenever they like.
 */
internal object WithheldOutput {

    /** Relative to the project root, and created on first use. */
    private const val DIRECTORY = ".cache"

    private val LOG = Logger.getInstance(WithheldOutput::class.java)

    private val FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /**
     * Writes [output] under the project's `.cache` and opens it in an editor tab. Null when there was
     * nowhere to put it or the write failed, which is the caller's cue not to offer Continue at all.
     *
     * EDT only, like [TranscriptExport.save]: it opens an editor, and the write it does first is a
     * few hundred kilobytes at worst.
     */
    fun saveAndOpen(project: Project, toolName: String, output: String): Path? {
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

        // Resolving the file through the VFS fires creation events, which is a write -- and this is
        // called from a Swing callback, which holds no write-intent lock. Same bargain as
        // [TranscriptExport], down to the reason: see https://jb.gg/ij-platform-threading.
        val file = findFile(path)
        if (file == null) {
            LOG.warn("Wrote the withheld output to $path but could not find it in the VFS")
            return null
        }
        FileEditorManager.getInstance(project).openFile(file, true)
        return path
    }

    /**
     * Reads the file back, or null when it is gone or unreadable.
     *
     * Through the open document rather than off the disk, and saving it first: the user has just been
     * editing this in an editor tab and has no reason to expect Ctrl+S to be what makes their edit
     * count. Sending the file as it sits on disk would quietly send the version *before* the trimming
     * that was the whole exercise. Only a file with no document behind it -- closed since, or never
     * opened -- is read directly.
     */
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

    /** Tool names are ours and are already `snake_case`, so this is a guard rather than a scrubber. */
    private fun sanitize(toolName: String): String =
        toolName.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            .ifEmpty { "tool" }
            .take(60)
}
