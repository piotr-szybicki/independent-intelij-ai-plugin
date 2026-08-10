package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool

class InsertMemberTool(private val project: Project) : AICodingAgentTool {

    override val name = "insert_member"
    override val description =
        "Inserts a new member (method, property, field, inner class) into an existing class, object, or interface."
    override val inputSchema: JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("path", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "File path relative to the project root")
            })
            add("class_name", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "Name of the target class, object, or interface")
            })
            add("member_code", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "Source code of the member to insert (method, property, field, etc.)")
            })
        })
        add("required", JsonArray().apply { add("path"); add("class_name"); add("member_code") })
    }

    override fun execute(input: JsonObject): String {
        val path = input.get("path")?.asString ?: return "Error: missing 'path'"
        val className = input.get("class_name")?.asString ?: return "Error: missing 'class_name'"
        val memberCode = input.get("member_code")?.asString ?: return "Error: missing 'member_code'"

        val vf = PsiTargets.resolveProjectFile(project, path)
            ?: return "Error: file not found or outside project: $path"

        val oldText = ReadAction.computeBlocking<String?, RuntimeException> {
            FileDocumentManager.getInstance().getDocument(vf)?.text
        } ?: return "Error: cannot read $path"

        val newText = computeNewText(oldText, className, memberCode)
            ?: return "Error: '$className' not found in $path"

        var error: String? = null
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project, "Insert Member", null, Runnable {
                val doc = FileDocumentManager.getInstance().getDocument(vf)
                if (doc == null) {
                    error = "Error: cannot open document for $path"
                    return@Runnable
                }
                doc.setText(newText)
            })
        }
        if (error != null) return error

        return "Inserted member into '$className' in $path."
    }

    private fun computeNewText(text: String, className: String, memberCode: String): String? {
        val classPattern = Regex("""\b(?:class|object|interface)\s+${Regex.escape(className)}\b""")
        val match = classPattern.find(text) ?: return null

        val openBrace = text.indexOf('{', match.range.last + 1)
        if (openBrace < 0) return null

        val closeBrace = findMatchingBrace(text, openBrace)
        if (closeBrace < 0) return null

        val indent = detectIndent(text, openBrace)
        val formattedMember = memberCode.trimEnd()
            .lines()
            .joinToString("\n") { line -> if (line.isBlank()) "" else "$indent$line" }

        return text.substring(0, closeBrace) + "\n$formattedMember\n" + text.substring(closeBrace)
    }

    private fun findMatchingBrace(text: String, openBraceOffset: Int): Int {
        var depth = 1
        var pos = openBraceOffset + 1
        while (pos < text.length && depth > 0) {
            when (text[pos]) {
                '{' -> depth++
                '}' -> depth--
            }
            if (depth > 0) pos++
        }
        return if (depth == 0) pos else -1
    }

    private fun detectIndent(text: String, openBraceOffset: Int): String {
        val nextNewline = text.indexOf('\n', openBraceOffset)
        if (nextNewline >= 0 && nextNewline + 1 < text.length) {
            val afterNewline = text.substring(nextNewline + 1)
            val indent = afterNewline.takeWhile { it == ' ' || it == '\t' }
            if (indent.isNotEmpty()) return indent
        }
        return "    "
    }
}
