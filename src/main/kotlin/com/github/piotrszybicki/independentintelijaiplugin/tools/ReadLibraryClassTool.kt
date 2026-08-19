package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool

class ReadLibraryClassTool(private val project: Project) : AICodingAgentTool {

    companion object {
        private const val MAX_CANDIDATES = 20

        private const val MAX_CHARS = 60_000
    }

    override val name = "read_library_class"
    override val description =
        "Reads a class from a library, framework or the JDK -- anything read_project_file cannot " +
            "reach. Takes a simple or qualified class name; qualify it if ambiguous. Returns real " +
            "source when a sources jar is present, decompiled bytecode otherwise. For a single " +
            "signature, use get_symbol_info."
    override val inputSchema: JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("name", JsonObject().apply {
                addProperty("type", "string")
                addProperty(
                    "description",
                    "Class name, e.g. \"RenameProcessor\" or \"com.intellij.refactoring.rename.RenameProcessor\"",
                )
            })
            add("start_line", JsonObject().apply {
                addProperty("type", "integer")
                addProperty("description", "First line to read, 1-based and inclusive. Defaults to 1.")
            })
            add("end_line", JsonObject().apply {
                addProperty("type", "integer")
                addProperty("description", "Last line to read, 1-based and inclusive. Defaults to the end.")
            })
        })
        add("required", JsonArray().apply { add("name") })
    }

    override fun execute(input: JsonObject): String {
        val name = input.get("name")?.asString?.trim().orEmpty()
        if (name.isEmpty()) return "Error: missing 'name'"

        val resolution = try {
            LibraryClasses.resolve(project, name, MAX_CANDIDATES)
        } catch (e: Exception) {
            return "Error: could not look up \"$name\": ${e.message ?: e::class.java.simpleName}"
        }

        val candidate = when (resolution) {
            is LibraryClasses.Resolution.NotFound ->
                return "No class named \"$name\" is visible to this project. Check the spelling, and " +
                    "note this searches the project's libraries and SDK -- a class from a dependency " +
                    "that is not on the classpath will not be found."

            is LibraryClasses.Resolution.Ambiguous ->
                return "\"$name\" is ambiguous — ${resolution.candidates.size} classes match:\n" +
                    resolution.candidates.joinToString("\n") { "  ${it.qualifiedName}" } +
                    "\n\nCall again with the fully qualified name."

            is LibraryClasses.Resolution.Found -> resolution.candidate
        }

        if (candidate.inProject) {
            val path = PsiTargets.relativePath(project, candidate.virtualFile)
            return "\"${candidate.qualifiedName}\" is part of this project, not a library. Use " +
                "read_project_file on $path -- it reads the in-memory version, so it sees edits " +
                "made this session that this tool would not."
        }

        val text = try {
            ReadAction.computeBlocking<String, RuntimeException> { candidate.file.text.orEmpty() }
        } catch (e: Exception) {
            return "Error: could not read ${candidate.qualifiedName}: " +
                (e.message ?: e::class.java.simpleName) +
                ". A compiled class needs the Java Bytecode Decompiler plugin to be readable; if it " +
                "is disabled, only the signature is available through get_symbol_info."
        }

        val range = LineRange(text)
        if (range.lineCount == 0) return "${candidate.qualifiedName} has no readable source."

        val start = input.get("start_line")?.asInt ?: 1
        val end = input.get("end_line")?.asInt ?: range.lineCount
        if (start < 1) return "Error: start_line must be 1 or greater"
        if (start > range.lineCount) {
            return "Error: start_line $start is past the end of ${candidate.qualifiedName} " +
                "(${range.lineCount} lines)"
        }
        if (end < start) return "Error: end_line ($end) must not be less than start_line ($start)"

        val lastLine = end.coerceAtMost(range.lineCount)
        val width = lastLine.toString().length
        val body = (start..lastLine).joinToString("\n") { line ->
            "%${width}d  %s".format(line, range.lines[line - 1])
        }

        val header = "${candidate.qualifiedName} — ${candidate.virtualFile.name}, " +
            "lines $start-$lastLine of ${range.lineCount}" + provenance(candidate)
        if (body.length <= MAX_CHARS) return "$header\n$body"

        val shown = body.take(MAX_CHARS).substringBeforeLast("\n")
        return "$header\n$shown\n\n[TRUNCATED at $MAX_CHARS characters. Request a narrower line " +
            "range to see the rest.]"
    }

    private fun provenance(candidate: LibraryClasses.Candidate): String {
        if (candidate.fromSources) return "  (source)"

        val library = candidate.libraryName?.let { " for $it" }.orEmpty()
        return "  (decompiled — no sources attached$library; comments and Javadoc are absent, and " +
            "parameter names may be synthetic. attach_library_sources can fetch them, with the " +
            "user's approval, if that missing detail actually matters here.)"
    }
}
