package org.jetbrains.plugins.template.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.search.searches.ReferencesSearch
import org.jetbrains.plugins.template.anthropic.AnthropicTool

class SafeDeleteTool(private val project: Project) : AnthropicTool {

    override val name = "safe_delete"
    override val description =
        "Deletes a symbol only if it has no remaining usages. Returns the list of references if the symbol is still in use."
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
                addProperty("description", "Name of the symbol to delete")
            })
        })
        add("required", JsonArray().apply { add("path"); add("line"); add("symbol") })
    }

    private data class DeleteInfo(
        val oldText: String,
        val newText: String,
        val usages: List<String>,
    )

    override fun execute(input: JsonObject): String {
        val path = input.get("path")?.asString ?: return "Error: missing 'path'"
        val line = input.get("line")?.asInt ?: return "Error: missing 'line'"
        val symbol = input.get("symbol")?.asString ?: return "Error: missing 'symbol'"

        val element = PsiTargets.resolveElement(project, path, line, symbol)
            ?: return "Error: could not resolve symbol '$symbol' at $path:$line"

        val info = ReadAction.compute<DeleteInfo?, RuntimeException> {
            val vf = element.containingFile?.virtualFile ?: return@compute null
            val doc = FileDocumentManager.getInstance().getDocument(vf) ?: return@compute null

            val usages = ReferencesSearch.search(element).mapNotNull { ref ->
                val refElement = ref.element
                val refFile = refElement.containingFile?.virtualFile ?: return@mapNotNull null
                val refDoc = FileDocumentManager.getInstance().getDocument(refFile) ?: return@mapNotNull null
                val refLine = refDoc.getLineNumber(refElement.textOffset) + 1
                "${PsiTargets.relativePath(project, refFile)}:$refLine"
            }

            val oldText = doc.text
            val startLine = doc.getLineNumber(element.textRange.startOffset)
            val endLine = doc.getLineNumber(element.textRange.endOffset)
            val removeStart = doc.getLineStartOffset(startLine)
            val removeEnd = if (endLine + 1 < doc.lineCount) doc.getLineStartOffset(endLine + 1) else doc.textLength
            val newText = oldText.removeRange(removeStart, removeEnd)

            DeleteInfo(oldText, newText, usages)
        } ?: return "Error: could not read file for '$symbol'"

        if (info.usages.isNotEmpty()) {
            return "Cannot safely delete '$symbol': still referenced at:\n${info.usages.joinToString("\n")}"
        }

        val vf = PsiTargets.resolveProjectFile(project, path)!!

        var error: String? = null
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project, "Safe Delete", null, Runnable {
                val doc = FileDocumentManager.getInstance().getDocument(vf)
                if (doc == null) {
                    error = "Error: cannot open document"
                    return@Runnable
                }
                doc.setText(info.newText)
            })
        }
        if (error != null) return error!!

        return "Deleted '$symbol' from $path."
    }
}
