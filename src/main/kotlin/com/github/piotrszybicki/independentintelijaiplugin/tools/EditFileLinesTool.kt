package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.github.piotrszybicki.independentintelijaiplugin.anthropic.AnthropicTool

/**
 * Line-addressed editing: the general-purpose escape hatch from the PSI-shaped tools, for changes
 * they have no vocabulary for.
 *
 * Writes a minimal range via `replaceString` rather than replacing the whole document, which keeps
 * folding, breakpoints and caret position intact and leaves the change session a tight hunk to
 * render instead of "every line changed".
 *
 * The line numbers are resolved inside the write action against the document's current text, so
 * they cannot go stale between the model's read and the write.
 */
class EditFileLinesTool(private val project: Project) : AnthropicTool {

    private companion object {
        const val REPLACE = "replace"
        const val INSERT = "insert"
    }

    override val name = "edit_file_lines"
    override val description =
        "Edits a file inside the project by line number. Line numbers are 1-based and inclusive, " +
            "matching what read_project_file returns -- read the file first to get them. " +
            "With mode='replace', lines start_line through end_line are removed and 'content' is " +
            "put in their place. With mode='insert', nothing is removed and 'content' is inserted " +
            "immediately above start_line (use start_line=1 to prepend to the file, or one past " +
            "the last line to append). Do not include line-number prefixes in 'content'."
    override val inputSchema: JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("path", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "File path relative to the project root, e.g. src/Main.kt")
            })
            add("mode", JsonObject().apply {
                addProperty("type", "string")
                add("enum", JsonArray().apply { add(REPLACE); add(INSERT) })
                addProperty("description", "'replace' to overwrite a line range, 'insert' to add lines without removing any")
            })
            add("start_line", JsonObject().apply {
                addProperty("type", "integer")
                addProperty(
                    "description",
                    "For 'replace', the first line to overwrite. For 'insert', the line the new " +
                        "content is placed above.",
                )
            })
            add("end_line", JsonObject().apply {
                addProperty("type", "integer")
                addProperty("description", "For 'replace', the last line to overwrite (inclusive). Ignored for 'insert'.")
            })
            add("content", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "The new lines, without line-number prefixes")
            })
        })
        add("required", JsonArray().apply { add("path"); add("mode"); add("start_line"); add("content") })
    }

    override fun execute(input: JsonObject): String {
        val path = input.get("path")?.asString ?: return "Error: missing 'path'"
        val mode = input.get("mode")?.asString ?: return "Error: missing 'mode'"
        if (mode != REPLACE && mode != INSERT) {
            return "Error: mode must be '$REPLACE' or '$INSERT', got '$mode'"
        }
        val startLine = input.get("start_line")?.asInt ?: return "Error: missing 'start_line'"
        val rawContent = input.get("content")?.asString ?: return "Error: missing 'content'"
        if (mode == REPLACE && input.get("end_line") == null) {
            return "Error: 'end_line' is required when mode is '$REPLACE'"
        }
        val endLine = input.get("end_line")?.asInt ?: startLine

        // Documents are always \n internally and setText/replaceString reject anything else. The
        // model has no way to know the file's on-disk separator, and IntelliJ restores it on save.
        val content = rawContent.replace("\r\n", "\n").replace('\r', '\n')

        val vf = PsiTargets.resolveProjectFile(project, path)
            ?: return "Error: file not found or outside project: $path"

        var result: String? = null
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project, "Edit Lines", null, Runnable {
                val document = FileDocumentManager.getInstance().getDocument(vf)
                if (document == null) {
                    result = "Error: cannot open document for $path"
                    return@Runnable
                }
                if (!document.isWritable) {
                    result = "Error: $path is read-only"
                    return@Runnable
                }

                val range = LineRange(document.text)
                val error = validate(mode, startLine, endLine, range, path)
                if (error != null) {
                    result = error
                    return@Runnable
                }

                val from: Int
                val to: Int
                val newText: String
                if (mode == REPLACE) {
                    from = range.startOffset(startLine)
                    to = range.startOffset(endLine + 1)
                    // Only the final line of a file may lack a trailing newline; anywhere else, one
                    // is required or the replacement fuses with the line below it.
                    newText = if (to < range.text.length && !content.endsWith("\n")) "$content\n" else content
                } else {
                    from = range.startOffset(startLine)
                    to = from
                    newText = when {
                        // Appending to a file whose last line has no newline: separate them first.
                        from == range.text.length && !range.endsWithNewline && range.text.isNotEmpty() ->
                            if (content.startsWith("\n")) content else "\n$content"
                        content.endsWith("\n") -> content
                        else -> "$content\n"
                    }
                }

                document.replaceString(from, to, newText)
                PsiDocumentManager.getInstance(project).commitDocument(document)

                val added = LineRange(newText).lineCount
                result = if (mode == REPLACE) {
                    val removed = endLine - startLine + 1
                    "Replaced lines $startLine-$endLine of $path ($removed line(s) removed, $added added)."
                } else {
                    "Inserted $added line(s) into $path above line $startLine."
                }
            })
        }

        return result ?: "Error: edit did not run"
    }

    private fun validate(mode: String, startLine: Int, endLine: Int, range: LineRange, path: String): String? {
        if (startLine < 1) return "Error: start_line must be 1 or greater"

        if (mode == INSERT) {
            // lineCount + 1 is legal and means "append after the last line".
            if (startLine > range.lineCount + 1) {
                return "Error: start_line $startLine is past the end of $path (${range.lineCount} lines); " +
                    "use ${range.lineCount + 1} to append"
            }
            return null
        }

        if (range.lineCount == 0) return "Error: $path is empty; use mode='$INSERT' to add lines"
        if (startLine > range.lineCount) {
            return "Error: start_line $startLine is past the end of $path (${range.lineCount} lines)"
        }
        if (endLine < startLine) {
            return "Error: end_line ($endLine) must not be less than start_line ($startLine)"
        }
        if (endLine > range.lineCount) {
            return "Error: end_line $endLine is past the end of $path (${range.lineCount} lines)"
        }
        return null
    }
}
