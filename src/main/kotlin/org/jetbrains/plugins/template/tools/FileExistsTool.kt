package org.jetbrains.plugins.template.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.template.anthropic.AnthropicTool

/**
 * Answers whether a project-relative path is there, so the model can pick between `create_file` and
 * `edit_file_lines` without burning a failed call on the wrong one.
 */
class FileExistsTool(private val project: Project) : AnthropicTool {

    override val name = "file_exists"
    override val description =
        "Checks whether a path exists inside the current project, given a path relative to the " +
            "project root. Reports whether it is a file or a directory. Use this before create_file " +
            "(which fails on an existing path) or before reading a file you are not sure is there."
    override val inputSchema: JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("path", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "Path relative to the project root, e.g. src/Main.kt")
            })
        })
        add("required", JsonArray().apply { add("path") })
    }

    override fun execute(input: JsonObject): String {
        val relativePath = input.get("path")?.asString ?: return "Error: missing 'path' argument"

        // Same containment check the reading tools use: a path escaping the project root must not be
        // probed, since even a yes/no answer leaks what is on the local disk.
        val target = PsiTargets.resolveProjectPath(project, relativePath)
            ?: return "Error: path is outside the project directory: $relativePath"

        // Prefer the VFS answer, which knows about files created this session before any disk refresh
        // has run; fall back to the disk for paths the VFS has not loaded.
        val fromVfs = ReadAction.compute<Boolean?, RuntimeException> {
            PsiTargets.resolveProjectFile(project, relativePath)?.takeIf { it.isValid }?.isDirectory
        }

        val isDirectory = when {
            fromVfs != null -> fromVfs
            target.exists() -> target.isDirectory
            else -> return "$relativePath does not exist."
        }

        return if (isDirectory) "$relativePath exists (directory)." else "$relativePath exists (file)."
    }
}
