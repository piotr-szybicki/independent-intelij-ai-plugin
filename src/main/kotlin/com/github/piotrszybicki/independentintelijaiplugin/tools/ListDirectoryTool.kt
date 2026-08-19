package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool

class ListDirectoryTool(private val project: Project) : AICodingAgentTool {

    companion object {
        private const val DEFAULT_MAX_ENTRIES = 200
        private const val MAX_MAX_ENTRIES = 2000
        private const val DEFAULT_MAX_DEPTH = 1
        private const val MAX_MAX_DEPTH = 25
    }

    override val name = "list_directory"
    override val description =
        "Lists a project directory's contents as an indented tree: each subdirectory on its own " +
            "line ending in \"/\", the files it holds comma-separated on the line below it, and " +
            "directories that contain only one subdirectory collapsed onto one line (\"a/b/c/\"). " +
            "Paths are relative to the listed directory. One level by default; recursive=true " +
            "descends, bounded by max_depth. Build output and VCS metadata are skipped."
    override val inputSchema: JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("path", JsonObject().apply {
                addProperty("type", "string")
                addProperty(
                    "description",
                    "Directory to list, relative to the project root. Defaults to the project root.",
                )
            })
            add("recursive", JsonObject().apply {
                addProperty("type", "boolean")
                addProperty("description", "Descend into subdirectories. Defaults to false.")
            })
            add("max_depth", JsonObject().apply {
                addProperty("type", "integer")
                addProperty(
                    "description",
                    "How many levels to descend when recursive is true, where 1 is the directory's " +
                        "own children. Defaults to $MAX_MAX_DEPTH (effectively unbounded).",
                )
            })
            add("max_entries", JsonObject().apply {
                addProperty("type", "integer")
                addProperty(
                    "description",
                    "Stop after this many entries. Defaults to $DEFAULT_MAX_ENTRIES, maximum $MAX_MAX_ENTRIES.",
                )
            })
        })
        add("required", JsonArray())
    }

    override fun execute(input: JsonObject): String {
        val relativePath = input.get("path")?.asString?.takeIf { it.isNotBlank() } ?: "."

        val root: VirtualFile = PsiTargets.resolveProjectFile(project, relativePath)
            ?: return "Error: path is outside the project directory or does not exist: $relativePath"
        if (!root.isDirectory) return "Error: not a directory: $relativePath"

        val recursive = input.get("recursive")?.asBoolean ?: false
        val maxDepth = if (!recursive) {
            1
        } else {
            (input.get("max_depth")?.asInt ?: MAX_MAX_DEPTH).coerceIn(1, MAX_MAX_DEPTH)
        }
        val maxEntries = (input.get("max_entries")?.asInt ?: DEFAULT_MAX_ENTRIES)
            .coerceIn(1, MAX_MAX_ENTRIES)

        val rootPath = PsiTargets.relativePath(project, root)
        val rootPrefix = if (rootPath.isEmpty()) "" else "$rootPath/"

        val entries = mutableListOf<DirectoryListing.Entry>()
        var truncated = false

        ProjectFiles.walk(project, root, maxDepth) { file, _ ->
            if (entries.size >= maxEntries) {
                truncated = true
                return@walk false
            }
            val path = PsiTargets.relativePath(project, file).removePrefix(rootPrefix)
            entries.add(DirectoryListing.Entry(path, file.isDirectory))
            true
        }

        return DirectoryListing.format(rootPath.ifEmpty { "." }, entries, truncated)
    }
}
