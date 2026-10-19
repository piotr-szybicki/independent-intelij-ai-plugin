package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.rename.RenameProcessor

class RenameSymbolTool(
    private val project: Project,
) : AICodingAgentTool {
    override val name = "rename_symbol"
    override val description =
        "Renames a symbol and all its usages across the project. Point it at the declaration or " +
            "any use. Library and SDK symbols cannot be renamed."
    override val inputSchema: JsonObject =
        PsiTargetSchema.create(
            symbolDescription = "Current name of the symbol to rename",
            extraProperties = mapOf("new_name" to ("string" to "New name for the symbol")),
            extraRequired = listOf("new_name"),
        )

    override fun execute(input: JsonObject): String {
        val targetInput = runCatching { PsiTargetInput.from(input) }.getOrElse { return "Error: ${it.message}" }
        val (path, line, symbol) = targetInput
        val newName = input.get("new_name")?.asString ?: return "Error: missing 'new_name'"

        val element =
            PsiTargets.resolveTarget(project, path, line, symbol)
                ?: return "Error: could not resolve symbol '$symbol' at $path:$line"

        if (!PsiTargets.isInProject(project, element)) {
            return "Error: '$symbol' is declared outside the project -- in a library or the SDK -- so it " +
                "cannot be renamed. Use get_symbol_info to see where it comes from."
        }

        val usageCount =
            ReadAction.computeBlocking<Int, RuntimeException> {
                ReferencesSearch.search(element).findAll().size
            }
        val declaredIn =
            ReadAction.computeBlocking<String, RuntimeException> {
                element.containingFile?.virtualFile?.let { PsiTargets.relativePath(project, it) } ?: path
            }

        var result = ""
        ApplicationManager.getApplication().invokeAndWait {
            result =
                try {
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
