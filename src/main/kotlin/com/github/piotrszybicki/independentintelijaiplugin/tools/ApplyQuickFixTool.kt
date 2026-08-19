package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.ShowIntentionsPass
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.util.Processor
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool

class ApplyQuickFixTool(private val project: Project) : AICodingAgentTool {

    companion object {
        private const val ANALYSIS_TIMEOUT_MILLIS = 10_000L
        private const val POLL_INTERVAL_MILLIS = 100L

        private const val MAX_LISTED = 25

        private const val ALL_PASSES = -1
    }

    override val name = "apply_quick_fix"
    override val description =
        "Applies an IDE quick fix (Alt+Enter) at a line -- the reliable way to fix an unresolved " +
            "reference, since the IDE knows the qualified name and adds the right import. Call it " +
            "without 'fix' to list what is available at that line, then again with a name " +
            "(case-insensitive, full or unique fragment). Opens the file in an editor."
    override val inputSchema: JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("path", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "File path relative to the project root, e.g. src/Main.kt")
            })
            add("line", JsonObject().apply {
                addProperty("type", "integer")
                addProperty(
                    "description",
                    "1-based line to act on, as reported by get_file_problems or read_project_file",
                )
            })
            add("fix", JsonObject().apply {
                addProperty("type", "string")
                addProperty(
                    "description",
                    "The fix to apply, exactly as listed by a previous call, or a unique fragment " +
                        "of it. Omit to list what is available instead of applying anything.",
                )
            })
        })
        add("required", JsonArray().apply { add("path"); add("line") })
    }

    override fun execute(input: JsonObject): String {
        val path = input.get("path")?.asString ?: return "Error: missing 'path'"
        val line = input.get("line")?.asInt ?: return "Error: missing 'line'"
        val requested = input.get("fix")?.asString?.trim()?.takeIf { it.isNotEmpty() }

        val vf = PsiTargets.resolveProjectFile(project, path)
            ?: return "Error: path is outside the project directory or does not exist: $path"
        if (vf.isDirectory) return "Error: not a file: $path"

        val editor = openEditor(vf, line)
            ?: return "Error: could not open $path in an editor, so its quick fixes cannot be reached."

        awaitAnalysis(vf)

        val fixes = collect(vf, editor, line)
        if (fixes.isEmpty()) {
            return "No quick fixes available at $path:$line. Either the IDE sees nothing wrong " +
                "there, or the problem has no automatic fix -- check get_file_problems for what it " +
                "actually reports on that line, and note the line must be the one the problem is " +
                "reported on, not the declaration it refers to."
        }

        if (requested == null) {
            val listed = fixes.take(MAX_LISTED).joinToString("\n") { "  ${it.label}" }
            val more = if (fixes.size > MAX_LISTED) "\n  ... and ${fixes.size - MAX_LISTED} more" else ""
            return "${fixes.size} quick fix(es) available at $path:$line:\n$listed$more\n\n" +
                "Call again with 'fix' set to one of these to apply it."
        }

        val matches = match(fixes, requested)
        if (matches.isEmpty()) {
            val listed = fixes.take(MAX_LISTED).joinToString("\n") { "  ${it.label}" }
            return "Error: no quick fix matching \"$requested\" at $path:$line. Available:\n$listed"
        }
        if (matches.size > 1) {
            val listed = matches.joinToString("\n") { "  ${it.label}" }
            return "Error: \"$requested\" matches ${matches.size} fixes at $path:$line. Be more " +
                "specific:\n$listed"
        }

        return apply(vf, editor, matches.single(), path, line)
    }

    private class Fix(val label: String, val descriptor: HighlightInfo.IntentionActionDescriptor)

    private fun match(fixes: List<Fix>, requested: String): List<Fix> {
        val wanted = requested.lowercase()
        val exact = fixes.filter { it.label.equals(requested, ignoreCase = true) }
        if (exact.isNotEmpty()) return exact
        return fixes.filter { it.label.lowercase().contains(wanted) }
    }

    private fun openEditor(vf: VirtualFile, line: Int): Editor? {
        var editor: Editor? = null
        ApplicationManager.getApplication().invokeAndWait {
            val descriptor = OpenFileDescriptor(project, vf, (line - 1).coerceAtLeast(0), 0)
            editor = FileEditorManager.getInstance(project).openTextEditor(descriptor, false)
        }
        return editor
    }

    private fun awaitAnalysis(vf: VirtualFile) {
        val deadline = System.currentTimeMillis() + ANALYSIS_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            val finished = ReadAction.computeBlocking<Boolean, RuntimeException> {
                val psiFile = PsiManager.getInstance(project).findFile(vf) ?: return@computeBlocking true
                DaemonCodeAnalyzerEx.getInstanceEx(project).isErrorAnalyzingFinished(psiFile)
            }
            if (finished) return
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
    }

    private fun collect(vf: VirtualFile, editor: Editor, line: Int): List<Fix> {
        var result: List<Fix> = emptyList()
        ApplicationManager.getApplication().invokeAndWait {
            result = ReadAction.computeBlocking<List<Fix>, RuntimeException> { gather(vf, editor, line) }
        }
        return result
    }

    private fun gather(vf: VirtualFile, editor: Editor, line: Int): List<Fix> {
        val psiFile = PsiManager.getInstance(project).findFile(vf) ?: return emptyList()
        val document = FileDocumentManager.getInstance().getDocument(vf) ?: return emptyList()

        val lineIndex = line - 1
        if (lineIndex < 0 || lineIndex >= document.lineCount) return emptyList()
        val lineStart = document.getLineStartOffset(lineIndex)
        val lineEnd = document.getLineEndOffset(lineIndex)

        val offsets = linkedSetOf(lineStart)
        DaemonCodeAnalyzerEx.processHighlights(
            document,
            project,
            HighlightSeverity.INFORMATION,
            lineStart,
            lineEnd,
            Processor { info ->
                offsets.add(info.startOffset.coerceIn(lineStart, lineEnd))
                true
            },
        )

        val byLabel = LinkedHashMap<String, Fix>()
        for (offset in offsets) {
            val available = try {
                ShowIntentionsPass.getAvailableFixes(editor, psiFile, ALL_PASSES, offset)
            } catch (e: Exception) {
                continue
            }
            for (descriptor in available) {
                val action = descriptor.action
                if (!action.isAvailable(project, editor, psiFile)) continue
                val label = action.text
                if (label.isNullOrBlank()) continue
                byLabel.putIfAbsent(label, Fix(label, descriptor))
            }
        }
        return byLabel.values.toList()
    }

    private fun apply(vf: VirtualFile, editor: Editor, fix: Fix, path: String, line: Int): String {
        val action = fix.descriptor.action
        var error: String? = null

        ApplicationManager.getApplication().invokeAndWait {
            val psiFile = PsiManager.getInstance(project).findFile(vf)
            if (psiFile == null) {
                error = "Error: could not open $path as a source file"
                return@invokeAndWait
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()

            try {
                if (action.startInWriteAction()) {
                    WriteCommandAction.runWriteCommandAction(project, fix.label, null, Runnable {
                        action.invoke(project, editor, psiFile)
                    })
                } else {
                    CommandProcessor.getInstance().executeCommand(
                        project,
                        { action.invoke(project, editor, psiFile) },
                        fix.label,
                        null,
                    )
                }
            } catch (e: Exception) {
                error = "Error applying \"${fix.label}\" at $path:$line: " +
                    (e.message ?: e::class.java.simpleName)
            }
        }

        error?.let { return it }
        return "Applied \"${fix.label}\" at $path:$line. The fix may have edited other lines or " +
            "other files; call get_file_problems to see what remains."
    }
}
