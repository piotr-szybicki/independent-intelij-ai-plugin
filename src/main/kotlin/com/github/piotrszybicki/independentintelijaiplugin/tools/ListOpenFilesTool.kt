package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.google.gson.JsonObject
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool

class ListOpenFilesTool(private val project: Project) : AICodingAgentTool {

    override val name = "list_open_files"
    override val description = "Lists the file paths currently open in the editor, relative to the project root."
    override val inputSchema: JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject())
    }

    override fun execute(input: JsonObject): String {
        val basePath = project.basePath
        val paths = ReadAction.computeBlocking<List<String>, RuntimeException> {
            FileEditorManager.getInstance(project).openFiles.map { file ->
                if (basePath != null && file.path.startsWith(basePath)) {
                    file.path.removePrefix(basePath).trimStart('/', '\\')
                } else {
                    file.path
                }
            }
        }
        return if (paths.isEmpty()) "No files are currently open." else paths.joinToString("\n")
    }
}
