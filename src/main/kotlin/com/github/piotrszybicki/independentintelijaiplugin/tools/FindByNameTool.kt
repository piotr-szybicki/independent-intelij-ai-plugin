package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.github.piotrszybicki.independentintelijaiplugin.anthropic.AnthropicTool

/**
 * Search by name rather than by content -- the IDE's Go to Class / Go to File / Find Action, for
 * when the thing being looked for is known by what it is called.
 *
 * find_in_files answers "where does this text appear"; this answers "where does this thing live".
 * Classes go through the same contributors that back Go to Class, so whichever languages the IDE
 * has support for are covered without this plugin knowing about any of them.
 */
class FindByNameTool(private val project: Project) : AnthropicTool {

    companion object {
        private const val DEFAULT_MAX_RESULTS = 50
        private const val MAX_MAX_RESULTS = 200

        /** Bounds the name scan on very large projects; the query filter runs over this many candidates. */
        private const val MAX_CANDIDATE_NAMES = 50_000
    }

    override val name = "find_by_name"
    override val description =
        "Finds things in the project by name, rather than by their contents. Set type to \"class\" " +
            "to find a class, interface or other top-level declaration (the IDE's Go to Class), " +
            "\"file\" to find a file by file name, or \"action\" to find an IDE action and its id " +
            "(the IDE's Find Action) -- an action id is what run_action takes. Matching is a " +
            "case-insensitive substring by default. Use find_in_files instead to search file contents."
    override val inputSchema: JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("query", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "The name, or part of it, to look for")
            })
            add("type", JsonObject().apply {
                addProperty("type", "string")
                add("enum", JsonArray().apply {
                    add("class")
                    add("file")
                    add("action")
                })
                addProperty("description", "What kind of thing to look for")
            })
            add("exact", JsonObject().apply {
                addProperty("type", "boolean")
                addProperty(
                    "description",
                    "Require the whole name to equal the query rather than contain it. Defaults to false.",
                )
            })
            add("max_results", JsonObject().apply {
                addProperty("type", "integer")
                addProperty(
                    "description",
                    "Stop after this many results. Defaults to $DEFAULT_MAX_RESULTS, maximum $MAX_MAX_RESULTS.",
                )
            })
        })
        add("required", JsonArray().apply {
            add("query")
            add("type")
        })
    }

    override fun execute(input: JsonObject): String {
        val query = input.get("query")?.asString?.trim().orEmpty()
        if (query.isEmpty()) return "Error: missing 'query'"

        val type = input.get("type")?.asString?.lowercase()
            ?: return "Error: missing 'type'. Use one of: class, file, action"

        val exact = input.get("exact")?.asBoolean ?: false
        val maxResults = (input.get("max_results")?.asInt ?: DEFAULT_MAX_RESULTS)
            .coerceIn(1, MAX_MAX_RESULTS)

        val matches: (String) -> Boolean = { candidate ->
            if (exact) candidate.equals(query, ignoreCase = true)
            else candidate.contains(query, ignoreCase = true)
        }

        val results = when (type) {
            "class" -> findClasses(matches, maxResults)
            "file" -> findFiles(matches, maxResults)
            "action" -> findActions(matches, maxResults)
            else -> return "Error: unknown type \"$type\". Use one of: class, file, action"
        }

        if (results.isEmpty()) return "No $type matching \"$query\"."

        return buildString {
            append("${results.size} $type result(s) for \"$query\"")
            if (results.size >= maxResults) append(" (stopped at the limit; narrow the query to see the rest)")
            append(":\n")
            results.forEach { append(it).append('\n') }
        }
    }

    // --- classes ------------------------------------------------------------------------------

    private fun findClasses(matches: (String) -> Boolean, maxResults: Int): List<String> {
        val results = mutableListOf<String>()

        for (contributor in ChooseByNameContributor.CLASS_EP_NAME.extensionList) {
            // A contributor that throws (an unhealthy language plugin, an index still building) must
            // not take the whole search down -- the others may still have the answer.
            val names = runCatching {
                ReadAction.compute<Array<String>, RuntimeException> {
                    contributor.getNames(project, false)
                }
            }.getOrDefault(emptyArray())

            for (candidateName in names.take(MAX_CANDIDATE_NAMES)) {
                if (!matches(candidateName)) continue

                val items = runCatching {
                    ReadAction.compute<Array<NavigationItem>, RuntimeException> {
                        // includeNonProjectItems stays false: library and JDK classes are not part
                        // of the project the user is asking about, and there are a great many of them.
                        contributor.getItemsByName(candidateName, candidateName, project, false)
                    }
                }.getOrDefault(emptyArray())

                for (item in items) {
                    results.add(renderNavigationItem(candidateName, item))
                    if (results.size >= maxResults) return results
                }
            }
        }
        return results
    }

    private fun renderNavigationItem(name: String, item: NavigationItem): String =
        ReadAction.compute<String, RuntimeException> {
            val element = item as? PsiElement
            val vf = element?.containingFile?.virtualFile
                ?: return@compute "$name (location unavailable)"

            val path = PsiTargets.relativePath(project, vf)
            val document = FileDocumentManager.getInstance().getDocument(vf)
            val offset = element.textOffset
            if (document == null || offset !in 0..document.textLength) return@compute "$name — $path"

            "$name — $path:${document.getLineNumber(offset) + 1}"
        }

    // --- files --------------------------------------------------------------------------------

    private fun findFiles(matches: (String) -> Boolean, maxResults: Int): List<String> {
        val results = mutableListOf<String>()
        val root = PsiTargets.resolveProjectFile(project, ".") ?: return results

        ProjectFiles.walk(project, root, Int.MAX_VALUE) { file, _ ->
            if (!file.isDirectory && matches(file.name)) {
                results.add(PsiTargets.relativePath(project, file))
            }
            results.size < maxResults
        }
        return results
    }

    // --- actions ------------------------------------------------------------------------------

    private fun findActions(matches: (String) -> Boolean, maxResults: Int): List<String> {
        val manager = ActionManager.getInstance()
        val results = mutableListOf<String>()

        for (id in manager.getActionIdList("")) {
            // An action's visible name is what a user would search for, but the id is what
            // run_action needs, so either matching is a hit and both go in the output.
            val text = runCatching { manager.getAction(id)?.templatePresentation?.text }.getOrNull()
            if (!matches(id) && !(text != null && matches(text))) continue

            results.add(if (text.isNullOrBlank()) id else "$id — $text")
            if (results.size >= maxResults) break
        }
        return results
    }
}
