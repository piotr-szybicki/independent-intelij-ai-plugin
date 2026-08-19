package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool

class ReadProjectFileTool(private val project: Project) : AICodingAgentTool {

    companion object {
        private const val MAX_CHARS = 100_000
    }

    override val name = "read_project_file"
    override val description =
        "Reads a project file, returning the requested lines each prefixed with its line number, " +
            "plus the total line count. Omit start_line/end_line for the whole file. Those line " +
            "numbers are what edit_file_lines takes; never include the prefixes in content passed " +
            "back. For a file you do not already know, outline it with get_file_structure first " +
            "and read the range it gives for the declaration you want, rather than the whole file."
    override val inputSchema: JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("path", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "File path relative to the project root, e.g. src/Main.kt")
            })
            add("start_line", JsonObject().apply {
                addProperty("type", "integer")
                addProperty("description", "First line to read, 1-based and inclusive. Defaults to 1.")
            })
            add("end_line", JsonObject().apply {
                addProperty("type", "integer")
                addProperty(
                    "description",
                    "Last line to read, 1-based and inclusive. Defaults to the end of the file.",
                )
            })
        })
        add("required", JsonArray().apply { add("path") })
    }

    override fun execute(input: JsonObject): String {
        val relativePath = input.get("path")?.asString ?: return "Error: missing 'path' argument"

        val target = PsiTargets.resolveProjectPath(project, relativePath)
            ?: return "Error: path is outside the project directory"
        if (!target.exists() || !target.isFile) {
            return "Error: file not found: $relativePath"
        }

        val text = ReadAction.computeBlocking<String, RuntimeException> {
            val vf = PsiTargets.resolveProjectFile(project, relativePath)
            val document = vf?.let { FileDocumentManager.getInstance().getDocument(it) }
            document?.text ?: target.readText()
        }

        val range = LineRange(text)
        if (range.lineCount == 0) return "$relativePath is empty (0 lines)."

        val start = (input.get("start_line")?.asInt ?: 1)
        val end = (input.get("end_line")?.asInt ?: range.lineCount)
        if (start < 1) return "Error: start_line must be 1 or greater"
        if (start > range.lineCount) {
            return "Error: start_line $start is past the end of $relativePath (${range.lineCount} lines)"
        }
        if (end < start) return "Error: end_line ($end) must not be less than start_line ($start)"

        val lastLine = end.coerceAtMost(range.lineCount)
        val width = lastLine.toString().length
        val body = (start..lastLine).joinToString("\n") { line ->
            "%${width}d  %s".format(line, range.lines[line - 1])
        }

        val header = "$relativePath: lines $start-$lastLine of ${range.lineCount}"
        if (body.length <= MAX_CHARS) return "$header\n$body"

        val shown = body.take(MAX_CHARS).substringBeforeLast("\n")
        return "$header\n$shown\n\n[TRUNCATED at $MAX_CHARS characters. Request a narrower line " +
            "range to see the rest -- the lines above are complete, but the range you asked for is not.]"
    }
}
