package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.codeInsight.CodeSmellInfo
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.CodeSmellDetector
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.github.piotrszybicki.independentintelijaiplugin.anthropic.AnthropicTool

/**
 * Reports what the IDE's own analysis thinks is wrong with a file -- unresolved references, type
 * errors, failed inspections.
 *
 * This is the edit-verify loop, and the reason it is worth having an agent inside the IDE rather
 * than driving one from a terminal: the answer comes from the same analysis that draws the squiggles,
 * in milliseconds, against the in-memory document. Building through run_shell_command would take
 * seconds, need an approval, and read the file off disk -- where the edits made this session have
 * not been saved yet.
 */
class GetFileProblemsTool(private val project: Project) : AnthropicTool {

    companion object {
        private const val DEFAULT_MAX_PROBLEMS = 100
        private const val MAX_MAX_PROBLEMS = 500

        /** Inspection messages can carry a whole HTML explanation; the first line is the useful part. */
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
        "Runs the IDE's code analysis on a file and returns its errors and warnings -- unresolved " +
            "references, type errors, and inspection findings -- as path:line:column with the " +
            "message. This is the fastest way to check whether an edit was valid: it analyses the " +
            "unsaved in-memory file, so it sees changes you have just made and a build would not. " +
            "Call it after editing a file, and prefer it over running a build to check for mistakes."
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

    /** A problem from either analysis path, with the 1-based coordinates the other tools speak. */
    private class Problem(
        val severity: HighlightSeverity,
        val line: Int,
        val column: Int?,
        val description: String,
    )

    /**
     * Gets the file's problems, preferring the highlights the editor's own daemon has already
     * computed.
     *
     * The fallback, CodeSmellDetector, is the platform's on-demand "analyse this file now" entry
     * point, but it asserts it was called from the UI thread -- it opens a modal progress dialog --
     * so it has to be hopped onto the EDT, and the user sees that dialog every time. Reading the
     * daemon's results costs neither, which matters because this tool is meant to be called after
     * every edit. The daemon only has results for files open in an editor, hence the fallback.
     */
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

    /**
     * The daemon's highlights for [vf], or null when it has not finished analysing the file -- which
     * is not the same as the file having no problems, and must not be reported as such.
     */
    private fun cachedHighlights(vf: VirtualFile, minSeverity: HighlightSeverity): List<Problem>? =
        ReadAction.compute<List<Problem>?, RuntimeException> {
            val psiFile = PsiManager.getInstance(project).findFile(vf) ?: return@compute null
            if (!DaemonCodeAnalyzerEx.getInstanceEx(project).isErrorAnalyzingFinished(psiFile)) {
                return@compute null
            }
            val document = FileDocumentManager.getInstance().getDocument(vf) ?: return@compute null

            DaemonCodeAnalyzerImpl.getHighlights(document, minSeverity, project).map { info ->
                val offset = info.startOffset.coerceIn(0, document.textLength)
                val line = document.getLineNumber(offset)
                Problem(
                    info.severity,
                    line + 1,
                    offset - document.getLineStartOffset(line) + 1,
                    describe(info.description ?: ""),
                )
            }
        }

    private fun describe(raw: String): String {
        val text = raw.replace('\n', ' ').trim()
        return if (text.length <= MAX_DESCRIPTION_CHARS) text else text.take(MAX_DESCRIPTION_CHARS) + "…"
    }
}
