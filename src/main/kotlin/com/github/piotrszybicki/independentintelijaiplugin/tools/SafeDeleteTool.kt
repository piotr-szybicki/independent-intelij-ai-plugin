package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.searches.ReferencesSearch
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool

class SafeDeleteTool(private val project: Project) : AICodingAgentTool {

    override val name = "safe_delete"
    override val description =
        "Deletes a symbol only if it has no remaining usages, returning the references if it is " +
            "still in use. Point it at the declaration or any use; either way the declaration is " +
            "removed. Library and SDK symbols cannot be deleted."
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
                addProperty("description", "Name of the symbol to delete")
            })
        })
        add("required", JsonArray().apply { add("path"); add("line"); add("symbol") })
    }

    private data class DeleteInfo(
        /** The file holding the *declaration*, which is not necessarily the one the caller named. */
        val file: VirtualFile,
        val oldText: String,
        val newText: String,
        val usages: List<String>,
    )

    override fun execute(input: JsonObject): String {
        val path = input.get("path")?.asString ?: return "Error: missing 'path'"
        val line = input.get("line")?.asInt ?: return "Error: missing 'line'"
        val symbol = input.get("symbol")?.asString ?: return "Error: missing 'symbol'"

        val element = PsiTargets.resolveTarget(project, path, line, symbol)
            ?: return "Error: could not resolve symbol '$symbol' at $path:$line"

        // A use site resolves to wherever the symbol actually comes from, which may be a jar.
        if (!PsiTargets.isInProject(project, element)) {
            return "Error: '$symbol' is declared outside the project -- in a library or the SDK -- so it " +
                "cannot be deleted. Use get_symbol_info to see where it comes from."
        }

        val info = ReadAction.computeBlocking<DeleteInfo?, RuntimeException> {
            val vf = element.containingFile?.virtualFile ?: return@computeBlocking null
            val doc = FileDocumentManager.getInstance().getDocument(vf) ?: return@computeBlocking null

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

            DeleteInfo(vf, oldText, newText, usages)
        } ?: return "Error: could not read file for '$symbol'"

        if (info.usages.isNotEmpty()) {
            return "Cannot safely delete '$symbol': still referenced at:\n${info.usages.joinToString("\n")}"
        }

        val vf = info.file
        val declaredIn = PsiTargets.relativePath(project, vf)

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
        if (error != null) return error

        return "Deleted '$symbol' from $declaredIn."
    }
}
