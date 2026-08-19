package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.codeInsight.CodeSmellInfo
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.CodeSmellDetector
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool

class GetFileProblemsTool(private val project: Project) : AICodingAgentTool {

    companion object {
        private const val DEFAULT_MAX_PROBLEMS = 100
        private const val MAX_MAX_PROBLEMS = 500

        private const val MAX_DESCRIPTION_CHARS = 300

        private val SEVERITIES = mapOf(
            "error" to HighlightSeverity.ERROR,
            "warning" to HighlightSeverity.WARNING,
            "weak_warning" to HighlightSeverity.WEAK_WARNING,
            "information" to HighlightSeverity.INFORMATION,
        )
    }

    override val name = "get_file_problems"
    override val description =
        "Runs the IDE's analysis on a file and returns errors and warnings as path:line:column " +
            "with the message. It analyses the unsaved in-memory file, so it sees edits a build " +
            "would not -- call it after editing instead of building. Fix what it reports with " +
            "apply_quick_fix where you can."
    override val inputSchema: JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("path", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "File path relative to the project root, e.g. src/Main.kt")
            })
            add("min_severity", JsonObject().apply {
                addProperty("type", "string")
                add("enum", JsonArray().apply { SEVERITIES.keys.forEach { add(it) } })
                addProperty(
                    "description",
                    "Lowest severity to report. Defaults to \"warning\"; use \"error\" to see only " +
                        "what actually breaks the build.",
                )
            })
            add("max_problems", JsonObject().apply {
                addProperty("type", "integer")
                addProperty(
                    "description",
                    "Stop after this many problems. Defaults to $DEFAULT_MAX_PROBLEMS, maximum $MAX_MAX_PROBLEMS.",
                )
            })
        })
        add("required", JsonArray().apply { add("path") })
    }

    override fun execute(input: JsonObject): String {
        val relativePath = input.get("path")?.asString ?: return "Error: missing 'path' argument"

        val vf = PsiTargets.resolveProjectFile(project, relativePath)
            ?: return "Error: path is outside the project directory or does not exist: $relativePath"
        if (vf.isDirectory) return "Error: not a file: $relativePath"

        val severityName = input.get("min_severity")?.asString?.lowercase() ?: "warning"
        val minSeverity = SEVERITIES[severityName]
            ?: return "Error: unknown min_severity \"$severityName\". Use one of: ${SEVERITIES.keys.joinToString(", ")}"

        val maxProblems = (input.get("max_problems")?.asInt ?: DEFAULT_MAX_PROBLEMS)
            .coerceIn(1, MAX_MAX_PROBLEMS)

        val found = try {
            collect(vf, minSeverity)
        } catch (e: ProcessCanceledException) {
            return "Error: the analysis of $relativePath was cancelled before it finished"
        } catch (e: Exception) {
            return "Error: could not analyse $relativePath: ${e.message ?: e::class.java.simpleName}"
        }

        val problems = found
            .filter { it.severity >= minSeverity }
            .sortedWith(compareByDescending<Problem> { it.severity }.thenBy { it.line })

        if (problems.isEmpty()) {
            return "No problems at severity $severityName or above in $relativePath."
        }

        val shown = problems.take(maxProblems)
        return buildString {
            append("${problems.size} problem(s) in $relativePath")
            if (shown.size < problems.size) append(", showing the first ${shown.size}")
            append(":\n")
            shown.forEach { problem ->
                append(relativePath)
                append(':').append(problem.line)
                problem.column?.let { append(':').append(it) }
                append(" [").append(problem.severity.name).append("] ")
                append(problem.description)
                append('\n')
            }
        }
    }

    private class Problem(
        val severity: HighlightSeverity,
        val line: Int,
        val column: Int?,
        val description: String,
    )

    private fun collect(vf: VirtualFile, minSeverity: HighlightSeverity): List<Problem> {
        cachedHighlights(vf, minSeverity)?.let { return it }

        var result: List<CodeSmellInfo> = emptyList()
        var failure: Exception? = null
        ApplicationManager.getApplication().invokeAndWait {
            try {
                result = CodeSmellDetector.getInstance(project).findCodeSmells(listOf(vf))
            } catch (e: Exception) {
                failure = e
            }
        }
        failure?.let { throw it }

        // CodeSmellInfo reports 0-based coordinates.
        return result.map { Problem(it.severity, it.startLine + 1, it.startColumn + 1, describe(it.description)) }
    }

    private fun cachedHighlights(vf: VirtualFile, minSeverity: HighlightSeverity): List<Problem>? =
        ReadAction.computeBlocking<List<Problem>?, RuntimeException> {
            val psiFile = PsiManager.getInstance(project).findFile(vf) ?: return@computeBlocking null
            if (!DaemonCodeAnalyzerEx.getInstanceEx(project).isErrorAnalyzingFinished(psiFile)) {
                return@computeBlocking null
            }
            val document = FileDocumentManager.getInstance().getDocument(vf) ?: return@computeBlocking null

            // processHighlights is the public form of what the daemon holds: the highlights the
            // markup model carries for [document], filtered to [minSeverity] and above. Its
            // impl-class sibling, DaemonCodeAnalyzerImpl.getHighlights, is @ApiStatus.Internal
            // and fails the plugin verifier.
            val problems = mutableListOf<Problem>()
            DaemonCodeAnalyzerEx.processHighlights(
                document, project, minSeverity, 0, document.textLength,
            ) { info: HighlightInfo ->
                val offset = info.startOffset.coerceIn(0, document.textLength)
                val line = document.getLineNumber(offset)
                problems += Problem(
                    info.severity,
                    line + 1,
                    offset - document.getLineStartOffset(line) + 1,
                    describe(info.description ?: ""),
                )
                true
            }
            problems
        }

    private fun describe(raw: String): String {
        val text = raw.replace('\n', ' ').trim()
        return if (text.length <= MAX_DESCRIPTION_CHARS) text else text.take(MAX_DESCRIPTION_CHARS) + "…"
    }
}
