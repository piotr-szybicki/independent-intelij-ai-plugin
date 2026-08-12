package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.google.gson.JsonObject
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool

/**
 * The Structure tool window, as a tool: what is in a file and on which line, without reading it.
 *
 * Built on the platform's own structure view model rather than a PSI walk, for two reasons. It
 * already knows what counts as a member in each language -- a raw walk over named elements returns
 * imports, parameters and local variables, which is noise. And its presentable text is the rendered
 * signature, so a method comes back as `doThing(x: Int): String` rather than just a name.
 *
 * The line numbers are the point as much as the names are: they are what `read_project_file` takes
 * as a range and what `edit_file_lines` takes as a target, so an outline turns "read the whole file
 * to find one method" into "read fifteen lines". The same applies to `class_name`, where the file is
 * a dependency read through `read_library_class` -- a decompiled class is often thousands of lines,
 * and outlining it first is the difference between an API you can survey and a wall of text.
 */
class GetFileStructureTool(private val project: Project) : AICodingAgentTool {

    companion object {
        private const val DEFAULT_MAX_DEPTH = 3
        private const val MAX_MAX_DEPTH = 10
        private const val DEFAULT_MAX_ITEMS = 300
        private const val MAX_MAX_ITEMS = 1000
        private const val MAX_CANDIDATES = 20
        private const val MAX_ERROR_CHARS = 500
    }

    override val name = "get_file_structure"
    override val description =
        "Outlines a file's classes, methods, fields and other declarations with their start lines " +
            "(Structure view). Far cheaper than reading the whole file, and the lines feed " +
            "read_project_file and edit_file_lines. Pass 'path' for a project file, or " +
            "'class_name' for a library or JDK class, whose lines feed read_library_class. For " +
            "one known symbol, use get_symbol_info."
    override val inputSchema: JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("path", JsonObject().apply {
                addProperty("type", "string")
                addProperty(
                    "description",
                    "File path relative to the project root, e.g. src/Main.kt. Give this or 'class_name'.",
                )
            })
            add("class_name", JsonObject().apply {
                addProperty("type", "string")
                addProperty(
                    "description",
                    "Class from a library, framework or the JDK, simple or fully qualified. Use " +
                        "instead of 'path' for anything outside the project.",
                )
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
    }

    override fun execute(input: JsonObject): String {
        val path = input.get("path")?.asString?.trim()?.takeIf { it.isNotEmpty() }
        val className = input.get("class_name")?.asString?.trim()?.takeIf { it.isNotEmpty() }

        if (path == null && className == null) return "Error: give either 'path' or 'class_name'"
        if (path != null && className != null) {
            return "Error: give either 'path' or 'class_name', not both"
        }

        val maxDepth = (input.get("max_depth")?.asInt ?: DEFAULT_MAX_DEPTH).coerceIn(1, MAX_MAX_DEPTH)
        val maxItems = (input.get("max_items")?.asInt ?: DEFAULT_MAX_ITEMS).coerceIn(1, MAX_MAX_ITEMS)

        val source = if (path != null) fromPath(path) else fromClassName(className!!)
        val target = when (source) {
            is Source.Error -> return source.message
            is Source.Resolved -> source
        }

        val outline = try {
            compute(target.file, maxDepth, maxItems)
        } catch (e: ProcessCanceledException) {
            return "Error: outlining ${target.label} was cancelled before it finished."
        } catch (e: Exception) {
            return "Error: could not outline ${target.label}: ${describe(e)}"
        } ?: return "Error: could not read ${target.label}."

        if (!outline.supported) {
            return "No structure available for ${target.label} -- the IDE has no outline for this " +
                "file type. Use ${if (path != null) "read_project_file" else "read_library_class"} instead."
        }
        if (outline.entries.isEmpty()) {
            return "${target.label} has no declarations to outline (${outline.lineCount} lines)."
        }

        val width = outline.lineCount.toString().length
        val blank = " ".repeat(width)

        return buildString {
            append(target.label).append(" — ").append(outline.lineCount).append(" lines, ")
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

    private sealed class Source {
        class Resolved(val file: PsiFile, val label: String) : Source()
        class Error(val message: String) : Source()
    }

    private fun fromPath(path: String): Source {
        val vf = PsiTargets.resolveProjectFile(project, path)
            ?: return Source.Error(
                "Error: path is outside the project directory or does not exist: $path. For a class " +
                    "from a library or the JDK, use 'class_name' instead."
            )
        if (vf.isDirectory) return Source.Error("Error: not a file: $path")

        val psiFile = PsiTargets.resolvePsiFile(project, path)
            ?: return Source.Error("Error: could not open $path as a source file.")
        return Source.Resolved(psiFile, path)
    }

    private fun fromClassName(className: String): Source {
        val resolution = try {
            LibraryClasses.resolve(project, className, MAX_CANDIDATES)
        } catch (e: Exception) {
            return Source.Error("Error: could not look up \"$className\": ${e.message ?: e::class.java.simpleName}")
        }

        return when (resolution) {
            is LibraryClasses.Resolution.NotFound -> Source.Error(
                "No class named \"$className\" is visible to this project. Check the spelling, and " +
                    "note this searches the project's own sources plus its libraries and SDK."
            )

            is LibraryClasses.Resolution.Ambiguous -> Source.Error(
                "\"$className\" is ambiguous — ${resolution.candidates.size} classes match:\n" +
                    resolution.candidates.joinToString("\n") { "  ${it.qualifiedName}" } +
                    "\n\nCall again with the fully qualified name."
            )

            is LibraryClasses.Resolution.Found ->
                Source.Resolved(resolution.candidate.file, resolution.candidate.qualifiedName)
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

    /**
     * A read action on the calling background thread, and deliberately *not* a hop onto the EDT.
     *
     * Kotlin's structure view renders each declaration's signature through the K2 Analysis API, and
     * that API refuses to run on the UI thread -- it throws `Analysis is not allowed: Called in the
     * EDT thread.` So building the model inside `invokeAndWait` made this tool fail for every single
     * `.kt` file, which is most of the files it is pointed at. Nothing in [build] wants the EDT: the
     * model is built from PSI and disposed without touching Swing.
     */
    private fun compute(psiFile: PsiFile, maxDepth: Int, maxItems: Int): Outline? =
        ReadAction.computeBlocking<Outline?, RuntimeException> { build(psiFile, maxDepth, maxItems) }

    /**
     * Names the exception as well as quoting it: a bare `e.message` is empty for plenty of platform
     * failures, which leaves the model with "could not outline Foo.kt" and nothing to act on. Capped
     * because the Kotlin plugin's exceptions carry attachments -- whole file contents, module layout
     * -- and dumping those into the transcript costs more than the outline was ever going to save.
     */
    private fun describe(e: Exception): String {
        val message = e.message?.replace('\n', ' ')?.trim()?.takeIf { it.isNotEmpty() }
            ?: return e::class.java.simpleName
        val capped = if (message.length <= MAX_ERROR_CHARS) message else message.take(MAX_ERROR_CHARS) + "…"
        return "${e::class.java.simpleName}: $capped"
    }

    private fun build(psiFile: PsiFile, maxDepth: Int, maxItems: Int): Outline? {
        // Line numbers come from the PSI text rather than a Document: PSI offsets are defined
        // against it, and a decompiled library class has no Document at all.
        val text = psiFile.text ?: return null
        val lines = Lines(text)

        val builder = LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(psiFile)
        if (builder !is TreeBasedStructureViewBuilder) {
            return Outline(emptyList(), lines.count, truncated = false, supported = false)
        }

        // No editor: the model only needs one to track the caret, which nothing here does.
        val model = builder.createStructureViewModel(null)
        try {
            val entries = mutableListOf<Entry>()
            val truncated = walk(model.root.children, 0, maxDepth, maxItems, entries, lines)
            return Outline(entries, lines.count, truncated, supported = true)
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
        lines: Lines,
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
                if (walk(element.children, depth, maxDepth, maxItems, entries, lines)) return true
                continue
            }

            val label = if (location.isNullOrEmpty()) text else "$text  $location"
            entries.add(Entry(depth, lineOf(element, lines), label))

            if (walk(element.children, depth + 1, maxDepth, maxItems, entries, lines)) return true
        }
        return false
    }

    private fun lineOf(element: TreeElement, lines: Lines): Int? {
        val psi = (element as? StructureViewTreeElement)?.value as? PsiElement ?: return null
        if (!psi.isValid) return null
        val range = psi.textRange ?: return null
        return lines.lineAt(range.startOffset)
    }

    /**
     * Line starts, indexed once so that resolving three hundred element offsets does not mean three
     * hundred scans of a file that may be tens of thousands of lines after decompilation.
     */
    private class Lines(text: String) {
        private val starts: IntArray

        val count: Int

        init {
            val split = LineRange(text).lines
            count = split.size
            starts = IntArray(split.size)
            var offset = 0
            for (i in split.indices) {
                starts[i] = offset
                offset += split[i].length + 1
            }
        }

        /** 1-based line containing [offset]. */
        fun lineAt(offset: Int): Int? {
            if (count == 0) return null
            val found = java.util.Arrays.binarySearch(starts, offset)
            // binarySearch returns -(insertionPoint) - 1 when there is no exact hit; the line we
            // want is the one starting just before the offset.
            val index = if (found >= 0) found else (-found - 2).coerceAtLeast(0)
            return index + 1
        }
    }
}
