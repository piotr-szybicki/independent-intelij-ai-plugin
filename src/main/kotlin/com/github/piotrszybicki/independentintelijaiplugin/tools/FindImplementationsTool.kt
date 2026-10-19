package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool
import com.google.gson.JsonObject
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.ElementDescriptionUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.usageView.UsageViewLongNameLocation
import com.intellij.usageView.UsageViewTypeLocation

class FindImplementationsTool(
    private val project: Project,
) : AICodingAgentTool {
    companion object {
        private const val DEFAULT_MAX_RESULTS = 100
        private const val MAX_MAX_RESULTS = 500
    }

    override val name = "find_implementations"
    override val description =
        "Finds implementations of a symbol: classes implementing an interface or extending an " +
            "abstract class, methods overriding a method (Go to Implementation). find_usages will " +
            "not find these. Point it at the declaration or any use; returns file:line."
    override val inputSchema: JsonObject =
        PsiTargetSchema.create(
            symbolDescription = "The name as it appears on that line -- just the identifier",
            includeOccurrence = true,
            extraProperties =
                mapOf(
                    "max_results" to
                        Pair(
                            "integer",
                            "Stop after this many. Defaults to $DEFAULT_MAX_RESULTS, maximum $MAX_MAX_RESULTS.",
                        ),
                ),
        )

    override fun execute(input: JsonObject): String {
        val targetInput = runCatching { PsiTargetInput.from(input) }.getOrElse { return "Error: ${it.message}" }
        val (path, line, symbol) = targetInput
        val occurrence = targetInput.occurrence

        val maxResults =
            (input.get("max_results")?.asInt ?: DEFAULT_MAX_RESULTS)
                .coerceIn(1, MAX_MAX_RESULTS)

        val target =
            PsiTargets.resolveTarget(project, path, line, symbol, occurrence)
                ?: return "Error: could not resolve '$symbol' at $path:$line. The name is matched as a " +
                    "whole word against that line's text -- check it appears there exactly as spelled."

        val hits =
            try {
                ReadAction.computeBlocking<List<Hit>, RuntimeException> { search(target) }
            } catch (e: Exception) {
                return "Error: could not search for implementations of '$symbol': " +
                    (e.message ?: e::class.java.simpleName)
            }

        if (hits.isEmpty()) {
            return "No implementations found for '$symbol'. Either nothing implements or overrides " +
                "it, or it is not the kind of symbol that has implementations -- a final class, or " +
                "a plain function. Use find_usages to find where it is referenced instead."
        }

        val shown = hits.take(maxResults)
        return buildString {
            append("${hits.size} implementation(s) of '$symbol'")
            if (shown.size < hits.size) append(", showing the first ${shown.size}")
            append(":\n")
            for (hit in shown) {
                append("  ").append(hit.location)
                append("  ").append(hit.label)
                if (!hit.inProject) append("  (outside the project)")
                append('\n')
            }
        }
    }

    private class Hit(
        val location: String,
        val label: String,
        val inProject: Boolean,
        val sortKey: String,
    )

    private fun search(target: PsiElement): List<Hit> {
        val found = DefinitionsScopedSearch.search(target).filter { it != target }

        return found
            .mapNotNull { describe(it) }
            .distinctBy { it.location + it.label }
            .sortedBy { it.sortKey }
    }

    private fun describe(element: PsiElement): Hit? {
        val kind =
            ElementDescriptionUtil
                .getElementDescription(element, UsageViewTypeLocation.INSTANCE)
                .ifBlank { "symbol" }
        val name =
            ElementDescriptionUtil
                .getElementDescription(element, UsageViewLongNameLocation.INSTANCE)
                .ifBlank {
                    element.text
                        ?.lineSequence()
                        ?.firstOrNull()
                        ?.trim()
                        ?.take(80)
                        .orEmpty()
                }

        val file = element.containingFile ?: return null
        val vf = file.virtualFile
        val inProject = vf != null && PsiTargets.isInProject(project, element)

        val lineNumber =
            vf?.let { virtualFile ->
                val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return@let null
                val offset = (element.textRange?.startOffset ?: 0).coerceIn(0, document.textLength)
                document.getLineNumber(offset) + 1
            }

        val where =
            when {
                vf == null -> file.name
                inProject -> PsiTargets.relativePath(project, vf)
                else -> vf.name
            }
        val location = if (lineNumber != null) "$where:$lineNumber" else where

        return Hit(location, "$kind $name", inProject, sortKey = "${if (inProject) 0 else 1}$where:$lineNumber")
    }
}
