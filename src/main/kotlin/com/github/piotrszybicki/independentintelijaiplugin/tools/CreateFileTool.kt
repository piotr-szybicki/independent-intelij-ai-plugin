package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.github.piotrszybicki.independentintelijaiplugin.anthropic.AnthropicTool

/**
 * Creates a new file, since [EditFileLinesTool] can only address lines that already exist.
 *
 * Refuses to touch an existing file rather than overwriting it: the model has no whole-file write
 * anywhere in the tool set, so a `create_file` that silently clobbered would be the one way to lose
 * a file's contents in a single call.
 */
class CreateFileTool(private val project: Project) : AnthropicTool {

    override val name = "create_file"
    override val description =
        "Creates a new file inside the project with the given content, along with any missing " +
            "parent directories. Fails if the file already exists -- use edit_file_lines to " +
            "change a file that is already there."
    override val inputSchema: JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("path", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "File path relative to the project root, e.g. src/Main.kt")
            })
            add("content", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "The contents of the new file. Pass an empty string for an empty file.")
            })
        })
        add("required", JsonArray().apply { add("path"); add("content") })
    }

    override fun execute(input: JsonObject): String {
        val path = input.get("path")?.asString ?: return "Error: missing 'path'"
        val rawContent = input.get("content")?.asString ?: return "Error: missing 'content'"

        // Documents are always \n internally; IntelliJ restores the platform separator on save.
        val content = rawContent.replace("\r\n", "\n").replace('\r', '\n')

        val target = PsiTargets.resolveProjectPath(project, path)
            ?: return "Error: path is outside the project directory: $path"
        if (target.isDirectory) return "Error: path is a directory: $path"
        if (target.exists()) {
            return "Error: $path already exists; use edit_file_lines to modify it"
        }
        if (target.name.isEmpty()) return "Error: path has no file name: $path"

        var result: String? = null
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project, "Create File", null, Runnable {
                val file = try {
                    create(target.parentFile.path, target.name)
                } catch (e: Exception) {
                    result = "Error: cannot create $path: ${e.message}"
                    return@Runnable
                }

                if (content.isEmpty()) {
                    result = "Created $path (empty)."
                    return@Runnable
                }

                val document = FileDocumentManager.getInstance().getDocument(file)
                if (document == null) {
                    // The file exists at this point but has no text document -- report it rather
                    // than leaving the model to assume the content was written.
                    result = "Error: created $path but cannot open it as text; it is left empty"
                    return@Runnable
                }

                document.setText(content)
                PsiDocumentManager.getInstance(project).commitDocument(document)
                result = "Created $path (${LineRange(content).lineCount} lines)."
            })
        }

        return result ?: "Error: create did not run"
    }

    private fun create(parentPath: String, fileName: String): VirtualFile {
        val parent = VfsUtil.createDirectoryIfMissing(parentPath)
            ?: throw IllegalStateException("could not create directory $parentPath")
        return parent.createChildData(this, fileName)
    }
}
