package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool

/**
 * What the user is looking at: the file in front of them, where the caret is, and what is selected.
 *
 * This is the tool that makes "fix this" and "explain this" mean anything. Without it the model has
 * to ask which file, which is the one question a person sitting in front of the file should never be
 * asked -- they already pointed at it, with their cursor.
 *
 * Returns the surrounding lines too, rather than only coordinates. "Explain this" answered with a
 * line number would just cost a `read_project_file` call to get back to where a human already was.
 */
class GetEditorContextTool(private val project: Project) : AICodingAgentTool {

    companion object {
        /** Lines either side of the caret when nothing is selected -- enough to see a small method. */
        private const val CONTEXT_LINES = 12

        /** A selection can be the whole file; past this the model should read the range deliberately. */
        private const val MAX_SELECTION_CHARS = 8_000
    }

    override val name = "get_editor_context"
    override val description =
        "Reports what the user currently has open and where their cursor is: the file, the caret " +
            "line and column, and the selected text if there is a selection. Call this first " +
            "whenever the user says \"this\", \"here\", \"the selected code\", \"this method\" or " +
            "similar without naming a file -- it is what those words refer to. Returns the " +
            "surrounding lines as well, so it usually answers the question on its own without a " +
            "further read."
    override val inputSchema: JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject())
    }

    override fun execute(input: JsonObject): String {
        // The selected editor is UI state and has to be read on the EDT; everything derived from it
        // is read there too, so the caret cannot move between reading the offset and the text.
        var result = ""
        ApplicationManager.getApplication().invokeAndWait { result = describe() }
        return result
    }

    private fun describe(): String {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
            ?: return "No file is open in the editor, so there is nothing the user could be " +
                "pointing at. Ask which file they mean."

        val document = editor.document
        val file = FileDocumentManager.getInstance().getFile(document)
        val path = file?.let { PsiTargets.relativePath(project, it) } ?: "an unsaved file"

        val caret = editor.caretModel
        val position = caret.logicalPosition
        val caretLine = position.line + 1

        val selection = editor.selectionModel
        val header = buildString {
            append("File: ").append(path)
            append("\nCaret: line ").append(caretLine).append(", column ").append(position.column + 1)
            if (caret.caretCount > 1) append("  (${caret.caretCount} carets; the primary one is shown)")
        }

        if (!selection.hasSelection()) {
            return "$header\nSelection: none\n\n" + window(editor, caretLine)
        }

        val startLine = document.getLineNumber(selection.selectionStart.coerceIn(0, document.textLength)) + 1
        val endLine = document.getLineNumber(selection.selectionEnd.coerceIn(0, document.textLength)) + 1
        val text = selection.selectedText.orEmpty()
        val shown = if (text.length <= MAX_SELECTION_CHARS) text else text.take(MAX_SELECTION_CHARS)

        return buildString {
            append(header)
            append("\nSelection: lines ").append(startLine).append('-').append(endLine)
            append(" (").append(text.length).append(" characters)")
            append("\n\nSelected text:\n")
            append(numbered(document.text, startLine, endLine, shown.length < text.length))
        }
    }

    /** The lines around [caretLine], marked so the model can see which one the caret is on. */
    private fun window(editor: Editor, caretLine: Int): String {
        val lines = LineRange(editor.document.text).lines
        if (lines.isEmpty()) return "The file is empty."

        val first = (caretLine - CONTEXT_LINES).coerceAtLeast(1)
        val last = (caretLine + CONTEXT_LINES).coerceAtMost(lines.size)
        val width = last.toString().length

        return buildString {
            append("Around the caret:\n")
            for (n in first..last) {
                // The caret line is marked rather than left to be counted off against the header.
                append(if (n == caretLine) ">" else " ")
                append("%${width}d  ".format(n))
                append(lines[n - 1])
                append('\n')
            }
        }
    }

    /**
     * The selected lines, numbered from [startLine] so the numbers match the file rather than the
     * selection -- they are what `edit_file_lines` takes.
     */
    private fun numbered(fileText: String, startLine: Int, endLine: Int, truncated: Boolean): String {
        val lines = LineRange(fileText).lines
        val last = endLine.coerceAtMost(lines.size)
        val width = last.toString().length

        return buildString {
            for (n in startLine..last) {
                append("%${width}d  ".format(n))
                append(lines[n - 1])
                append('\n')
            }
            if (truncated) {
                append("\n[Selection truncated at $MAX_SELECTION_CHARS characters. ")
                append("Use read_project_file for the full range.]")
            }
        }
    }
}
