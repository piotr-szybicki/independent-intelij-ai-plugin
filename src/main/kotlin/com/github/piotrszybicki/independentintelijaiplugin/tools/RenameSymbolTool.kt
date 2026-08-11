package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.rename.RenameProcessor
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool

class RenameSymbolTool(private val project: Project) : AICodingAgentTool {

    override val name = "rename_symbol"
    override val description =
        "Renames a symbol and all its usages across the project. Point it at the declaration or " +
            "any use. Library and SDK symbols cannot be renamed."
    override val inputSchema: JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("path", JsonObject().apply {
                addProperty("type", "string")
                addProperty(
                    "description",
                    "File path relative to the project root, at a line where the symbol is declared or used",
                )
            })
            add("line", JsonObject().apply {
                addProperty("type", "integer")
                addProperty("description", "1-based line number of the declaration or the use")
            })
            add("symbol", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "Current name of the symbol to rename")
            })
            add("new_name", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "New name for the symbol")
            })
        })
        add("required", JsonArray().apply { add("path"); add("line"); add("symbol"); add("new_name") })
    }

    override fun execute(input: JsonObject): String {
        val path = input.get("path")?.asString ?: return "Error: missing 'path'"
        val line = input.get("line")?.asInt ?: return "Error: missing 'line'"
        val symbol = input.get("symbol")?.asString ?: return "Error: missing 'symbol'"
        val newName = input.get("new_name")?.asString ?: return "Error: missing 'new_name'"

        val element = PsiTargets.resolveTarget(project, path, line, symbol)
            ?: return "Error: could not resolve symbol '$symbol' at $path:$line"

        // A use site resolves to wherever the symbol actually comes from, which may be a jar.
        if (!PsiTargets.isInProject(project, element)) {
            return "Error: '$symbol' is declared outside the project -- in a library or the SDK -- so it " +
                "cannot be renamed. Use get_symbol_info to see where it comes from."
        }

        val usageCount = ReadAction.computeBlocking<Int, RuntimeException> {
            ReferencesSearch.search(element).findAll().size
        }
        // Not necessarily $path: the model may have pointed at a use rather than the declaration.
        val declaredIn = ReadAction.computeBlocking<String, RuntimeException> {
            element.containingFile?.virtualFile?.let { PsiTargets.relativePath(project, it) } ?: path
        }

        var result = ""
        ApplicationManager.getApplication().invokeAndWait {
            result = try {
                val processor = RenameProcessor(project, element, newName, false, false)
                processor.setPreviewUsages(false)
                processor.run()
                "Renamed '$symbol' to '$newName' (declared in $declaredIn, $usageCount reference(s) updated)."
            } catch (e: Exception) {
                "Error renaming '$symbol': ${e.message}"
            }
        }
        return result
    }
}
