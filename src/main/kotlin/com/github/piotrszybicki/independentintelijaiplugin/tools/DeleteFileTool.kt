package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool

class DeleteFileTool(private val project: Project) : AICodingAgentTool {

    override val name = "delete_file"
    override val description =
        "Deletes a project file. Refuses directories. To remove a single class or function from a " +
            "file that should stay, use safe_delete."
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
