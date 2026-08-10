package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.ElementDescriptionUtil
import com.intellij.psi.PsiElement
import com.intellij.usageView.UsageViewLongNameLocation
import com.intellij.usageView.UsageViewTypeLocation
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool

/**
 * Go to Declaration, as a tool. Answers "what is this?" about a symbol the model is looking at,
 * without it having to guess which file holds the definition and read the whole thing to find one
 * signature.
 *
 * The declaration's *source* comes back, not just its location: returning `File.kt:47` would only
 * force a `read_project_file` call afterwards, which is the round trip this exists to remove.
 */
class GetSymbolInfoTool(private val project: Project) : AICodingAgentTool {

    companion object {
        /** Enough for a whole method, or a class header with its first few members. */
        private const val MAX_LINES = 40
        private const val MAX_CHARS = 8_000
    }

    override val name = "get_symbol_info"
    override val description =
        "Resolves the symbol at a file and line to its declaration and shows it: what kind of thing " +
            "it is, its qualified name, where it is declared, and its source including any doc " +
            "comment. This is the IDE's Go to Declaration -- point it at a *use* of a symbol (a " +
            "call, a type reference, an inferred receiver) and it finds the definition, including " +
            "for symbols from libraries or the JDK that no project file contains. Pointing it at " +
            "the declaration itself works too. Prefer it over reading a whole file to learn one " +
            "signature, and use it before find_usages or rename_symbol when you are unsure which " +
            "declaration a name refers to."
    override val inputSchema: JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("path", JsonObject().apply {
                addProperty("type", "string")
                addProperty(
                    "description",
                    "File path relative to the project root, at a line where the symbol is used or declared",
                )
            })
            add("line", JsonObject().apply {
                addProperty("type", "integer")
                addProperty("description", "1-based line number, matching what read_project_file returns")
            })
            add("symbol", JsonObject().apply {
                addProperty("type", "string")
                addProperty(
                    "description",
                    "The name as it appears on that line -- just the identifier, not the whole expression",
                )
            })
            add("occurrence", JsonObject().apply {
                addProperty("type", "integer")
                addProperty(
                    "description",
                    "Which appearance of the name on that line to resolve, 1-based, left to right. Defaults to 1.",
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

        val target = PsiTargets.resolveTarget(project, path, line, symbol, occurrence)
            ?: return "Error: could not resolve '$symbol' at $path:$line. The name is matched as a " +
                "whole word against that line's text -- check it appears there exactly as spelled, " +
                "and pass 'occurrence' if it appears more than once."

        return ReadAction.computeBlocking<String, RuntimeException> { describe(target) }
    }

    private fun describe(target: PsiElement): String {
        // A symbol from a compiled jar resolves to a stub whose text is a decompiled skeleton. The
        // navigation element is the real source when the library ships one, so prefer it.
        val element = target.navigationElement ?: target

        val kind = ElementDescriptionUtil.getElementDescription(element, UsageViewTypeLocation.INSTANCE)
            .ifBlank { "symbol" }
        val qualifiedName = ElementDescriptionUtil.getElementDescription(element, UsageViewLongNameLocation.INSTANCE)
            .ifBlank { element.text.lineSequence().first().trim().take(120) }
        val header = "$kind  $qualifiedName"

        val file = element.containingFile
        val text = file?.text
        // Both are absent for synthetic elements -- a generated getter, a light class -- which
        // resolve fine but have no source to point at.
        val range = element.textRange
        if (file == null || text == null || range == null) return "$header\n(no source available)"

        val startLine = lineOf(text, range.startOffset)
        val endLine = lineOf(text, range.endOffset)

        val vf = file.virtualFile
        val inProject = vf != null && PsiTargets.isInProject(project, element)
        val location = when {
            vf == null -> "(in memory, no file on disk)"
            inProject -> "${PsiTargets.relativePath(project, vf)}:$startLine-$endLine"
            else -> "${vf.name}:$startLine-$endLine  (outside the project -- library or SDK source, " +
                "not editable and not reachable with read_project_file)"
        }

        val lines = LineRange(text).lines
        val lastLine = minOf(endLine, startLine + MAX_LINES - 1, lines.size)
        val width = lastLine.toString().length
        val body = (startLine..lastLine)
            .joinToString("\n") { n -> "%${width}d  %s".format(n, lines[n - 1]) }
            .take(MAX_CHARS)

        return buildString {
            appendLine(header)
            appendLine("declared in: $location")
            appendLine()
            append(body)
            if (lastLine < endLine) {
                appendLine()
                append("[Showing lines $startLine-$lastLine of the declaration, which ends at line $endLine.")
                if (inProject) append(" Use read_project_file for the rest.")
                append("]")
            }
        }
    }

    /** 1-based line containing [offset], counted the same way [LineRange] splits the file. */
    private fun lineOf(text: String, offset: Int): Int {
        var line = 1
        for (i in 0 until offset.coerceIn(0, text.length)) {
            if (text[i] == '\n') line++
        }
        return line
    }
}
