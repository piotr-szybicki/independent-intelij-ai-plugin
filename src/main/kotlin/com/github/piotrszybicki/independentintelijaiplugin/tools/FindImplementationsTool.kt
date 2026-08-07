package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.ElementDescriptionUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.usageView.UsageViewLongNameLocation
import com.intellij.usageView.UsageViewTypeLocation
import com.github.piotrszybicki.independentintelijaiplugin.anthropic.AnthropicTool

/**
 * Go to Implementation, as a tool: who implements this interface, and what overrides this method.
 *
 * `find_usages` answers "where is this mentioned", which is a different question -- a call to
 * `execute()` on the interface is a usage, and the twenty classes that actually implement it are
 * not. Nothing else in the toolset could reach them.
 *
 * Built on [DefinitionsScopedSearch] rather than `ClassInheritorsSearch` and
 * `OverridingMethodsSearch` deliberately. Those two live in the Java plugin, so using them would
 * mean depending on `com.intellij.modules.java` and refusing to load in PyCharm, WebStorm, GoLand
 * and Rider. This one is platform, is what Ctrl+Alt+B uses, and covers both directions of the
 * question through whatever language support the IDE happens to have.
 */
class FindImplementationsTool(private val project: Project) : AnthropicTool {

    companion object {
        private const val DEFAULT_MAX_RESULTS = 100
        private const val MAX_MAX_RESULTS = 500
    }

    override val name = "find_implementations"
    override val description =
        "Finds the implementations of a symbol: the classes implementing an interface or extending " +
            "an abstract class, or the methods overriding a method. This is the IDE's Go to " +
            "Implementation. Use it for \"who implements this\" and \"what overrides this\" -- " +
            "find_usages answers a different question and will not find them, because a class that " +
            "implements an interface need never mention its methods by name. Point it at the " +
            "declaration or at any use of the symbol. Returns file:line for each result."
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
                addProperty("description", "The name as it appears on that line -- just the identifier")
            })
            add("occurrence", JsonObject().apply {
                addProperty("type", "integer")
                addProperty(
                    "description",
                    "Which appearance of the name on that line to use, 1-based. Defaults to 1.",
                )
            })
            add("max_results", JsonObject().apply {
                addProperty("type", "integer")
                addProperty(
                    "description",
                    "Stop after this many. Defaults to $DEFAULT_MAX_RESULTS, maximum $MAX_MAX_RESULTS.",
                )
            })
        })
        add("required", JsonArray().apply { add("path"); add("line"); add("symbol") })
    }

    override fun execute(input: JsonObject): String {
        val path = input.get("path")?.asString ?: return "Error: missing 'path'"
        val line = input.get("line")?.asInt ?: return "Error: missing 'line'"
        val symbol = input.get("symbol")?.asString ?: return "Error: missing 'symbol'"
        val occurrence = input.get("occurrence")?.asInt ?: 1
        if (occurrence < 1) return "Error: occurrence must be 1 or greater"

        val maxResults = (input.get("max_results")?.asInt ?: DEFAULT_MAX_RESULTS)
            .coerceIn(1, MAX_MAX_RESULTS)

        val target = PsiTargets.resolveTarget(project, path, line, symbol, occurrence)
            ?: return "Error: could not resolve '$symbol' at $path:$line. The name is matched as a " +
                "whole word against that line's text -- check it appears there exactly as spelled."

        val hits = try {
            ReadAction.compute<List<Hit>, RuntimeException> { search(target) }
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
        // The no-scope overload uses the element's own use scope, which is what Go to Implementation
        // does: project sources plus the libraries that can actually see it.
        val found = DefinitionsScopedSearch.search(target).filter { it != target }

        return found
            .mapNotNull { describe(it) }
            // Deduplicated on the rendered line: a light class and the declaration it stands for can
            // both come back for the same Kotlin type.
            .distinctBy { it.location + it.label }
            .sortedBy { it.sortKey }
    }

    private fun describe(element: PsiElement): Hit? {
        val kind = ElementDescriptionUtil.getElementDescription(element, UsageViewTypeLocation.INSTANCE)
            .ifBlank { "symbol" }
        val name = ElementDescriptionUtil.getElementDescription(element, UsageViewLongNameLocation.INSTANCE)
            .ifBlank { element.text?.lineSequence()?.firstOrNull()?.trim()?.take(80).orEmpty() }

        val file = element.containingFile ?: return null
        val vf = file.virtualFile
        val inProject = vf != null && PsiTargets.isInProject(project, element)

        val lineNumber = vf?.let { virtualFile ->
            val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return@let null
            val offset = (element.textRange?.startOffset ?: 0).coerceIn(0, document.textLength)
            document.getLineNumber(offset) + 1
        }

        val where = when {
            vf == null -> file.name
            inProject -> PsiTargets.relativePath(project, vf)
            else -> vf.name
        }
        val location = if (lineNumber != null) "$where:$lineNumber" else where

        return Hit(location, "$kind $name", inProject, sortKey = "${if (inProject) 0 else 1}$where:$lineNumber")
    }
}
