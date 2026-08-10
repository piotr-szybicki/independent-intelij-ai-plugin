package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool

/**
 * Deletes a file outright, for when the file itself is the thing that should go -- [SafeDeleteTool]
 * removes a single declaration and leaves the file behind.
 *
 * Refuses directories. A recursive directory delete is the one call here that could wipe out work
 * the session never looked at, and the model can always delete the files it actually means to.
 *
 * Note this is the one tool whose effect the Approve/Revert bar cannot undo: the change session
 * tracks document text, and a deleted file has no document left to restore. The platform's Local
 * History does keep the contents, which is what the result points the user at.
 */
class DeleteFileTool(private val project: Project) : AICodingAgentTool {

    override val name = "delete_file"
    override val description =
        "Deletes a file from the project, given a path relative to the project root. Refuses " +
            "directories -- delete their files individually. Use safe_delete instead to remove a " +
            "single class or function from a file that should stay."
    override val inputSchema: JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("path", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "File path relative to the project root, e.g. src/Main.kt")
            })
        })
        add("required", JsonArray().apply { add("path") })
    }

    override fun execute(input: JsonObject): String {
        val path = input.get("path")?.asString ?: return "Error: missing 'path'"

        val target = PsiTargets.resolveProjectPath(project, path)
            ?: return "Error: path is outside the project directory: $path"
        if (!target.exists()) return "Error: file not found: $path"
        if (target.isDirectory) return "Error: $path is a directory; delete its files individually"

        val vf = PsiTargets.resolveProjectFile(project, path)
            ?: return "Error: $path is not loaded in the IDE's file system"

        var error: String? = null
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project, "Delete File", null, Runnable {
                try {
                    vf.delete(this)
                } catch (e: Exception) {
                    error = "Error: cannot delete $path: ${e.message}"
                }
            })
        }
        error?.let { return it }

        return "Deleted $path. This cannot be undone from the chat's Revert button; " +
            "the contents are recoverable from Local History."
    }
}
