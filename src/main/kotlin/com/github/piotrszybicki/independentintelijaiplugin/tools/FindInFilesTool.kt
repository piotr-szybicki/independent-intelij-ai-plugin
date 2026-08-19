package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.find.FindModel
import com.intellij.find.impl.FindInProjectUtil
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.usageView.UsageInfo
import com.intellij.usages.UsageViewPresentation
import com.intellij.util.Processor
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool
import com.github.piotrszybicki.independentintelijaiplugin.settings.AgentConfigurations
import com.github.piotrszybicki.independentintelijaiplugin.settings.FindInFilesConfig

class FindInFilesTool(private val project: Project) : AICodingAgentTool {

    companion object {
        private val LOG = Logger.getInstance(FindInFilesTool::class.java)

        private const val DEFAULT_MAX_FILES = 100
        private const val MAX_MAX_FILES = 100

        private const val MAX_TOTAL_MATCHES = 2_000
    }

    private enum class Limit { NONE, FILES, MATCHES }

    override val name = "find_in_files"
    override val description =
        "Plain text search across project files (Find in Files). Supports regex, case " +
            "sensitivity, whole-word, a file mask such as \"*.kt\", and a subdirectory scope. " +
            "Results are locations only, grouped by file: the file's path on its own line, then " +
            "the line number of each match indented below it. Read the file to see the matching " +
            "text. To find uses of a declaration, use find_usages."
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
            add("max_files", JsonObject().apply {
                addProperty("type", "integer")
                addProperty(
                    "description",
                    "Stop once matches have been found in this many files. Defaults to " +
                        "$DEFAULT_MAX_FILES, which is also the maximum. Every match inside those " +
                        "files is reported.",
                )
            })
        })
        add("required", JsonArray().apply { add("query") })
    }

    override fun execute(input: JsonObject): String {
        val query = input.get("query")?.asString.orEmpty()
        if (query.isEmpty()) return "Error: missing 'query'"

        blockedBy(query)?.let { phrase ->
            // Named rather than refused blankly, so the next attempt is a different question rather
            // than the same one in different case.
            return "Error: \"$phrase\" is blocked in this project's \"${FindInFilesConfig.SECTION}\" " +
                "settings -- it matches too much of the project to be worth listing. Search for " +
                "something more specific, or use find_usages if it is a declaration you are after."
        }

        // "max_results" is what this was called while it counted matches. A conversation that has
        // been going a while still has the old schema in it, and the old name means the new thing
        // closely enough that refusing it would be pedantry.
        val maxFiles = (input.get("max_files")?.asInt ?: input.get("max_results")?.asInt ?: DEFAULT_MAX_FILES)
            .coerceIn(1, MAX_MAX_FILES)

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
        val files = mutableSetOf<VirtualFile>()
        var limit = Limit.NONE
        val presentation = FindInProjectUtil.setupProcessPresentation(UsageViewPresentation())

        val failure = runCatching {
            // The engine expects a progress indicator on the thread; without one it has nothing to
            // report cancellation through. Ours is a placeholder -- the caps are what bound the run.
            ProgressManager.getInstance().runProcess({
                FindInProjectUtil.findUsages(
                    model,
                    project,
                    Processor { usage ->
                        // Which file a hit is in is a PSI question, so it needs read access; the
                        // engine usually has it already, and asking again while it does is free.
                        val file = ReadAction.compute<VirtualFile?, RuntimeException> { usage.virtualFile }
                            ?: return@Processor true
                        // Hits arrive from several threads, so the counting that decides when to
                        // stop has to happen under one lock.
                        synchronized(found) {
                            if (file !in files && files.size >= maxFiles) {
                                limit = Limit.FILES
                                return@synchronized false
                            }
                            files.add(file)
                            found.add(usage)
                            if (found.size >= MAX_TOTAL_MATCHES) {
                                limit = Limit.MATCHES
                                return@synchronized false
                            }
                            true
                        }
                    },
                    presentation,
                )
            }, EmptyProgressIndicator())
        }.exceptionOrNull()

        failure?.let {
            // A bad regular expression is the overwhelmingly likely cause, and the model can fix it.
            return "Error: the search failed: ${it.message ?: it::class.java.simpleName}"
        }

        return render(query, found, limit)
    }

    private fun blockedBy(query: String): String? {
        val loaded = AgentConfigurations.getInstance(project).findInFiles()
        loaded.error?.let { LOG.warn("blocking no find_in_files query: $it") }
        return loaded.findInFiles.blocking(query)
    }

    private fun render(query: String, usages: List<UsageInfo>, limit: Limit): String {
        val matches = ReadAction.computeBlocking<List<MatchListing.Match>, RuntimeException> {
            usages.mapNotNull { usage ->
                val file = usage.virtualFile ?: return@mapNotNull null
                val document = FileDocumentManager.getInstance().getDocument(file) ?: return@mapNotNull null
                val offset = usage.navigationOffset.takeIf { it in 0..document.textLength }
                    ?: return@mapNotNull null

                // No text: the location is the whole answer -- see the note on the class.
                MatchListing.Match(PsiTargets.relativePath(project, file), document.getLineNumber(offset) + 1)
            }
        }
        if (matches.isEmpty()) return "No matches for \"$query\"."

        val fileCount = matches.distinctBy { it.path }.size
        val summary = buildString {
            append("${MatchListing.count(matches)} line(s) in $fileCount file(s) for \"$query\"")
            // Which cap ran out decides what a narrower query has to change: fewer files is a
            // different word, while a hit on the line ceiling inside a handful of files is a mask
            // or a directory away from being answerable.
            when (limit) {
                Limit.FILES -> append(", the most files this tool lists")
                Limit.MATCHES -> append(", the most lines this tool lists")
                Limit.NONE -> Unit
            }
        }
        return MatchListing.format(summary, matches, truncated = limit != Limit.NONE)
    }
}
