package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool
import com.google.gson.JsonObject
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.search.searches.ReferencesSearch

class FindUsagesTool(
    private val project: Project,
) : AICodingAgentTool {
    override val name = "find_usages"
    override val description =
        "Finds all usages (references) of a symbol. Point it at the declaration or at any use. " +
            "Returns file:line locations."
    override val inputSchema: JsonObject =
        PsiTargetSchema.create(symbolDescription = "Name of the symbol to search for")

    override fun execute(input: JsonObject): String {
        val targetInput = runCatching { PsiTargetInput.from(input) }.getOrElse { return "Error: ${it.message}" }
        val (path, line, symbol) = targetInput

        val element =
            PsiTargets.resolveTarget(project, path, line, symbol)
                ?: return "Error: could not resolve symbol '$symbol' at $path:$line"

        val usages =
            ReadAction.computeBlocking<List<String>, RuntimeException> {
                ReferencesSearch.search(element).mapNotNull { ref ->
                    val refElement = ref.element
                    val refFile = refElement.containingFile?.virtualFile ?: return@mapNotNull null
                    if (!ChatWorkingDirectory.contains(project, refFile.path)) return@mapNotNull null
                    val doc = FileDocumentManager.getInstance().getDocument(refFile) ?: return@mapNotNull null
                    val refLine = doc.getLineNumber(refElement.textOffset) + 1
                    "${PsiTargets.relativePath(project, refFile)}:$refLine"
                }
            }

        return if (usages.isEmpty()) {
            "No usages found for '$symbol'."
        } else {
            "Found ${usages.size} usage(s):\n${usages.joinToString("\n")}"
        }
    }
}
