package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.github.piotrszybicki.independentintelijaiplugin.anthropic.AnthropicTool

/**
 * The Structure tool window, as a tool: what is in a file and on which line, without reading the
 * file.
 *
 * Built on the platform's own structure view model rather than a PSI walk, for two reasons. It
 * already knows what counts as a member in each language -- a raw walk over named elements returns
 * imports, parameters and local variables, which is noise. And its presentable text is the rendered
 * signature, so a method comes back as `doThing(x: Int): String` rather than just a name.
 *
 * The line numbers are the point as much as the names are: they are what `read_project_file` takes
 * as a range and what `edit_file_lines` takes as a target, so an outline turns "read the whole file
 * to find one method" into "read fifteen lines".
 */
class GetFileStructureTool(private val project: Project) : AnthropicTool {

    companion object {
        private const val DEFAULT_MAX_DEPTH = 3
        private const val MAX_MAX_DEPTH = 10
        private const val DEFAULT_MAX_ITEMS = 300
        private const val MAX_MAX_ITEMS = 1000
    }

    override val name = "get_file_structure"
    override val description =
        "Outlines a file: its classes, methods, fields and other declarations, each with the line " +
            "it starts on, as the IDE's Structure view shows them. Use this instead of reading a " +
            "whole file when you want to know what is in it or where something is defined -- it " +
            "costs a fraction of the tokens, and the line numbers it returns are what " +
            "read_project_file and edit_file_lines take, so you can then read or edit just the part " +
            "you need. Use get_symbol_info instead when you already know the name and want the " +
            "declaration itself."
    override val inputSchema: JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("path", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "File path relative to the project root, e.g. src/Main.kt")
            })
            add("max_depth", JsonObject().apply {
                addProperty("type", "integer")
                addProperty(
                    "description",
                    "How far to descend: 1 for top-level declarations only, 2 to include their " +
                        "members. Defaults to $DEFAULT_MAX_DEPTH, maximum $MAX_MAX_DEPTH.",
                )
            })
            add("max_items", JsonObject().apply {
                addProperty("type", "integer")
                addProperty(
                    "description",
                    "Stop after this many entries. Defaults to $DEFAULT_MAX_ITEMS, maximum $MAX_MAX_ITEMS.",
                )
            })
        })
        add("required", JsonArray().apply { add("path") })
    }

    override fun execute(input: JsonObject): String {
        val path = input.get("path")?.asString ?: return "Error: missing 'path'"

        val vf = PsiTargets.resolveProjectFile(project, path)
            ?: return "Error: path is outside the project directory or does not exist: $path"
        if (vf.isDirectory) return "Error: not a file: $path"

        val maxDepth = (input.get("max_depth")?.asInt ?: DEFAULT_MAX_DEPTH).coerceIn(1, MAX_MAX_DEPTH)
        val maxItems = (input.get("max_items")?.asInt ?: DEFAULT_MAX_ITEMS).coerceIn(1, MAX_MAX_ITEMS)

        val outline = try {
            compute(vf, maxDepth, maxItems)
        } catch (e: Exception) {
            return "Error: could not outline $path: ${e.message ?: e::class.java.simpleName}"
        } ?: return "Error: could not open $path as a source file."

        if (!outline.supported) {
            return "No structure available for $path -- the IDE has no outline for this file type. " +
                "Use read_project_file instead."
        }
        if (outline.entries.isEmpty()) {
            return "$path has no declarations to outline (${outline.lineCount} lines)."
        }

        val width = outline.lineCount.toString().length
        val blank = " ".repeat(width)

        return buildString {
            append(path).append(" — ").append(outline.lineCount).append(" lines, ")
            append(outline.entries.size).append(" declaration(s)")
            if (outline.truncated) append(" (truncated at $maxItems)")
            append(":\n")
            for (entry in outline.entries) {
                if (entry.line != null) append("%${width}d".format(entry.line)) else append(blank)
                append("  ")
                append("  ".repeat(entry.depth))
                append(entry.label)
                append('\n')
            }
        }
    }

    private class Entry(val depth: Int, val line: Int?, val label: String)

    private class Outline(
        val entries: List<Entry>,
        val lineCount: Int,
        val truncated: Boolean,
        /** False when the language contributes no structure view -- not the same as an empty file. */
        val supported: Boolean,
    )

    private fun compute(vf: VirtualFile, maxDepth: Int, maxItems: Int): Outline? {
        var result: Outline? = null
        ApplicationManager.getApplication().invokeAndWait {
            result = ReadAction.compute<Outline?, RuntimeException> { build(vf, maxDepth, maxItems) }
        }
        return result
    }

    private fun build(vf: VirtualFile, maxDepth: Int, maxItems: Int): Outline? {
        val psiFile = PsiManager.getInstance(project).findFile(vf) ?: return null
        val document = FileDocumentManager.getInstance().getDocument(vf) ?: return null
        val lineCount = document.lineCount

        val builder = LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(psiFile)
        if (builder !is TreeBasedStructureViewBuilder) {
            return Outline(emptyList(), lineCount, truncated = false, supported = false)
        }

        // No editor: the model only needs one to track the caret, which nothing here does.
        val model = builder.createStructureViewModel(null)
        try {
            val entries = mutableListOf<Entry>()
            val truncated = walk(model.root.children, 0, maxDepth, maxItems, entries, document)
            return Outline(entries, lineCount, truncated, supported = true)
        } finally {
            Disposer.dispose(model)
        }
    }

    /** Returns true if [maxItems] cut the walk short. */
    private fun walk(
        elements: Array<TreeElement>,
        depth: Int,
        maxDepth: Int,
        maxItems: Int,
        entries: MutableList<Entry>,
        document: Document,
    ): Boolean {
        if (depth >= maxDepth) return false

        for (element in elements) {
            if (entries.size >= maxItems) return true

            val presentation = element.presentation
            val text = presentation.presentableText?.trim().orEmpty()
            val location = presentation.locationString?.trim()

            // Some models wrap real members in unnamed grouping nodes. Those are not worth a line,
            // but their children are -- and indenting under a row that was never printed would be
            // misleading, so the children stay at this depth.
            if (text.isEmpty()) {
                if (walk(element.children, depth, maxDepth, maxItems, entries, document)) return true
                continue
            }

            val label = if (location.isNullOrEmpty()) text else "$text  $location"
            entries.add(Entry(depth, lineOf(element, document), label))

            if (walk(element.children, depth + 1, maxDepth, maxItems, entries, document)) return true
        }
        return false
    }

    private fun lineOf(element: TreeElement, document: Document): Int? {
        val psi = (element as? StructureViewTreeElement)?.value as? PsiElement ?: return null
        if (!psi.isValid) return null
        val range = psi.textRange ?: return null
        val offset = range.startOffset.coerceIn(0, document.textLength)
        return document.getLineNumber(offset) + 1
    }
}
