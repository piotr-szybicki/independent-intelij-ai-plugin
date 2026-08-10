package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool

class AddImportTool(private val project: Project) : AICodingAgentTool {

    override val name = "add_import"
    override val description =
        "Adds an import statement to a source file. No-op if the import already exists."
    override val inputSchema: JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("path", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "File path relative to the project root")
            })
            add("import_path", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "Fully qualified import path, e.g. java.util.List or kotlinx.coroutines.launch")
            })
        })
        add("required", JsonArray().apply { add("path"); add("import_path") })
    }

    override fun execute(input: JsonObject): String {
        val path = input.get("path")?.asString ?: return "Error: missing 'path'"
        val importPath = input.get("import_path")?.asString ?: return "Error: missing 'import_path'"

        val vf = PsiTargets.resolveProjectFile(project, path)
            ?: return "Error: file not found or outside project: $path"

        val oldText = ReadAction.computeBlocking<String?, RuntimeException> {
            FileDocumentManager.getInstance().getDocument(vf)?.text
        } ?: return "Error: cannot read $path"

        val importStatement = "import $importPath"
        if (oldText.contains(importStatement)) return "Import '$importPath' already exists in $path."

        val newText = computeNewText(oldText, importStatement)

        var error: String? = null
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project, "Add Import", null, Runnable {
                val doc = FileDocumentManager.getInstance().getDocument(vf)
                if (doc == null) {
                    error = "Error: cannot open document for $path"
                    return@Runnable
                }
                doc.setText(newText)
            })
        }
        if (error != null) return error

        return "Added import '$importPath' to $path."
    }

    private fun computeNewText(text: String, importStatement: String): String {
        val lines = text.split("\n")
        var lastImportIdx = -1
        var packageIdx = -1

        for ((i, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("import ")) lastImportIdx = i
            if (trimmed.startsWith("package ")) packageIdx = i
        }

        val result = lines.toMutableList()
        when {
            lastImportIdx >= 0 -> result.add(lastImportIdx + 1, importStatement)
            packageIdx >= 0 -> {
                result.add(packageIdx + 1, "")
                result.add(packageIdx + 2, importStatement)
            }
            else -> result.add(0, importStatement)
        }
        return result.joinToString("\n")
    }
}
