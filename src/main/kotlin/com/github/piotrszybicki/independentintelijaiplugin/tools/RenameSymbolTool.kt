package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.rename.RenameProcessor
import com.github.piotrszybicki.independentintelijaiplugin.anthropic.AnthropicTool

class RenameSymbolTool(private val project: Project) : AnthropicTool {

    override val name = "rename_symbol"
    override val description =
        "Renames a symbol and all its usages across the project. Updates references in every file."
    override val inputSchema: JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("path", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "File path relative to the project root where the symbol is declared")
            })
            add("line", JsonObject().apply {
                addProperty("type", "integer")
                addProperty("description", "1-based line number of the symbol declaration")
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

        val element = PsiTargets.resolveElement(project, path, line, symbol)
            ?: return "Error: could not resolve symbol '$symbol' at $path:$line"

        val usageCount = ReadAction.compute<Int, RuntimeException> {
            ReferencesSearch.search(element).findAll().size
        }

        var result = ""
        ApplicationManager.getApplication().invokeAndWait {
            result = try {
                val processor = RenameProcessor(project, element, newName, false, false)
                processor.setPreviewUsages(false)
                processor.run()
                "Renamed '$symbol' to '$newName' ($path:$line and $usageCount reference(s))."
            } catch (e: Exception) {
                "Error renaming '$symbol': ${e.message}"
            }
        }
        return result
    }
}
