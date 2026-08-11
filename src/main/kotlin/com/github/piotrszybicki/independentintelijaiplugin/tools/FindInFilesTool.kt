package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.find.FindModel
import com.intellij.find.impl.FindInProjectUtil
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.usageView.UsageInfo
import com.intellij.usages.FindUsagesProcessPresentation
import com.intellij.usages.UsageViewPresentation
import com.intellij.util.Processor
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool
import java.util.concurrent.atomic.AtomicInteger

/**
 * Text search across the project -- the IDE's Find in Files, driven from the chat.
 *
 * Uses the platform's own engine rather than walking the file system: it honours the project's
 * excluded folders and, more importantly, searches documents rather than disk, so text the edit
 * tools have written but the user has not saved yet is found too. A hand-rolled grep would miss
 * exactly the edits made earlier in the same conversation.
 *
 * Complements find_usages, which resolves a symbol through the PSI. This one has no idea what the
 * text means -- use it for strings, comments, config keys, and anything that is not a declaration.
 */
class FindInFilesTool(private val project: Project) : AICodingAgentTool {

    companion object {
        private const val DEFAULT_MAX_RESULTS = 100
        private const val MAX_MAX_RESULTS = 500

        /** Matching lines are echoed for context, not to reproduce the file. */
        private const val MAX_LINE_CHARS = 200
    }

    override val name = "find_in_files"
    override val description =
        "Plain text search across project files (Find in Files). Supports regex, case " +
            "sensitivity, whole-word, a file mask such as \"*.kt\", and a subdirectory scope. " +
            "Returns path:line with the matching line. To find uses of a declaration, use " +
            "find_usages."
    override val inputSchema: JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("query", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "Text to search for, or a regular expression when 'regex' is true")
            })
            add("regex", JsonObject().apply {
                addProperty("type", "boolean")
                addProperty("description", "Treat the query as a regular expression. Defaults to false.")
            })
            add("case_sensitive", JsonObject().apply {
                addProperty("type", "boolean")
                addProperty("description", "Match case exactly. Defaults to false.")
            })
            add("whole_words", JsonObject().apply {
                addProperty("type", "boolean")
                addProperty("description", "Match whole words only. Defaults to false.")
            })
            add("file_mask", JsonObject().apply {
                addProperty("type", "string")
                addProperty(
                    "description",
                    "Restrict to file names matching a mask, e.g. \"*.kt\" or \"*.{kt,java}\". " +
                        "Omit to search every file.",
                )
            })
            add("directory", JsonObject().apply {
                addProperty("type", "string")
                addProperty(
                    "description",
                    "Restrict to a directory, relative to the project root. Omit to search the whole project.",
                )
            })
            add("max_results", JsonObject().apply {
                addProperty("type", "integer")
                addProperty(
                    "description",
                    "Stop after this many matches. Defaults to $DEFAULT_MAX_RESULTS, maximum $MAX_MAX_RESULTS.",
                )
            })
        })
        add("required", JsonArray().apply { add("query") })
    }

    override fun execute(input: JsonObject): String {
        val query = input.get("query")?.asString.orEmpty()
        if (query.isEmpty()) return "Error: missing 'query'"

        val maxResults = (input.get("max_results")?.asInt ?: DEFAULT_MAX_RESULTS)
            .coerceIn(1, MAX_MAX_RESULTS)

        val model = FindModel().apply {
            stringToFind = query
            isCaseSensitive = input.get("case_sensitive")?.asBoolean ?: false
            isRegularExpressions = input.get("regex")?.asBoolean ?: false
            isWholeWordsOnly = input.get("whole_words")?.asBoolean ?: false
            searchContext = FindModel.SearchContext.ANY
            input.get("file_mask")?.asString?.takeIf { mask -> mask.isNotBlank() }?.let { fileFilter = it }
        }

        val directory = input.get("directory")?.asString
        if (directory.isNullOrBlank()) {
            model.isProjectScope = true
        } else {
            val dir = PsiTargets.resolveProjectPath(project, directory)
                ?: return "Error: directory is outside the project: $directory"
            if (!dir.isDirectory) return "Error: not a directory: $directory"
            model.isProjectScope = false
            model.directoryName = dir.path
            model.isWithSubdirectories = true
        }

        val found = mutableListOf<UsageInfo>()
        val seen = AtomicInteger(0)
        val presentation = FindInProjectUtil.setupProcessPresentation(UsageViewPresentation())

        val failure = runCatching {
            // The engine expects a progress indicator on the thread; without one it has nothing to
            // report cancellation through. Ours is a placeholder -- the caps are what bound the run.
            ProgressManager.getInstance().runProcess({
                FindInProjectUtil.findUsages(
                    model,
                    project,
                    Processor { usage ->
                        synchronized(found) { if (found.size < maxResults) found.add(usage) }
                        seen.incrementAndGet() < maxResults
                    },
                    presentation,
                )
            }, EmptyProgressIndicator())
        }.exceptionOrNull()

        failure?.let {
            // A bad regular expression is the overwhelmingly likely cause, and the model can fix it.
            return "Error: the search failed: ${it.message ?: it::class.java.simpleName}"
        }

        return render(query, found, seen.get() >= maxResults)
    }

    private fun render(query: String, usages: List<UsageInfo>, truncated: Boolean): String {
        if (usages.isEmpty()) return "No matches for \"$query\"."

        val lines = ReadAction.computeBlocking<List<String>, RuntimeException> {
            usages.mapNotNull { usage ->
                val file = usage.virtualFile ?: return@mapNotNull null
                val document = FileDocumentManager.getInstance().getDocument(file) ?: return@mapNotNull null
                val offset = usage.navigationOffset.takeIf { it in 0..document.textLength }
                    ?: return@mapNotNull null

                val line = document.getLineNumber(offset)
                "${PsiTargets.relativePath(project, file)}:${line + 1}: ${lineText(document, line)}"
            }
        }

        // Grouped only by being sorted: the engine walks file by file, so equal paths already sit
        // together, and a flat list stays greppable for the model.
        return buildString {
            append("${lines.size} match(es) for \"$query\"")
            if (truncated) append(" (stopped at the limit; narrow the query, mask or directory to see the rest)")
            append(":\n")
            lines.forEach { append(it).append('\n') }
        }
    }

    private fun lineText(document: Document, line: Int): String {
        val text = document.getText(
            com.intellij.openapi.util.TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line))
        ).trim()
        return if (text.length <= MAX_LINE_CHARS) text else text.take(MAX_LINE_CHARS) + "…"
    }
}
