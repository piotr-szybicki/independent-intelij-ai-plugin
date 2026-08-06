package org.jetbrains.plugins.template.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.search.searches.ReferencesSearch
import org.jetbrains.plugins.template.anthropic.AnthropicTool

class FindUsagesTool(private val project: Project) : AnthropicTool {

    override val name = "find_usages"
    override val description =
        "Finds all usages (references) of a symbol in the project. Returns a list of file:line locations."
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
                addProperty("description", "Name of the symbol to search for")
            })
        })
        add("required", JsonArray().apply { add("path"); add("line"); add("symbol") })
    }

    override fun execute(input: JsonObject): String {
        val path = input.get("path")?.asString ?: return "Error: missing 'path'"
        val line = input.get("line")?.asInt ?: return "Error: missing 'line'"
        val symbol = input.get("symbol")?.asString ?: return "Error: missing 'symbol'"

        val element = PsiTargets.resolveElement(project, path, line, symbol)
            ?: return "Error: could not resolve symbol '$symbol' at $path:$line"

        val usages = ReadAction.compute<List<String>, RuntimeException> {
            ReferencesSearch.search(element).mapNotNull { ref ->
                val refElement = ref.element
                val refFile = refElement.containingFile?.virtualFile ?: return@mapNotNull null
                val doc = FileDocumentManager.getInstance().getDocument(refFile) ?: return@mapNotNull null
                val refLine = doc.getLineNumber(refElement.textOffset) + 1
                "${PsiTargets.relativePath(project, refFile)}:$refLine"
            }
        }

        return if (usages.isEmpty()) "No usages found for '$symbol'."
        else "Found ${usages.size} usage(s):\n${usages.joinToString("\n")}"
    }
}
