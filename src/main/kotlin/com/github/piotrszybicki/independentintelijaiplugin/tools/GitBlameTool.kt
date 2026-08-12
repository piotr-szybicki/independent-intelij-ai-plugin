package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool
import git4idea.commands.GitCommand

/**
 * Which commit last touched each line -- the tool for "why is this here", where find_usages answers
 * "what uses this".
 *
 * Blames the committed file, not the editor's copy: lines this session changed are attributed to
 * whoever last committed them, and lines it added are not in the output at all.
 */
class GitBlameTool(private val project: Project) : AICodingAgentTool {

    companion object {
        private const val MAX_CHARS = 20_000
    }

    override val name = "git_blame"
    override val description =
        "Per line of a file: the commit, author and date that last changed it. Uncommitted edits " +
            "are not reflected."
    override val inputSchema: JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("path", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "File to blame, relative to the project root.")
            })
            add("start_line", JsonObject().apply {
                addProperty("type", "integer")
                addProperty("description", "First line to blame, 1-based and inclusive. Defaults to the whole file.")
            })
            add("end_line", JsonObject().apply {
                addProperty("type", "integer")
                addProperty("description", "Last line to blame, 1-based and inclusive.")
            })
        })
        add("required", JsonArray().apply { add("path") })
    }

    override fun execute(input: JsonObject): String {
        val path = input.get("path")?.asString ?: return "Error: missing 'path' argument"
        val resolved = when (val result = GitRepositories.resolve(project, path)) {
            is GitRepositories.Resolved.Error -> return result.message
            is GitRepositories.Resolved.Ok -> result
        }
        val pathInRepo = resolved.pathInRepo ?: return "Error: 'path' must name a file, not the repository root"

        val startLine = input.get("start_line")?.asInt
        val endLine = input.get("end_line")?.asInt
        if (startLine != null && startLine < 1) return "Error: start_line must be 1 or greater"
        if (startLine != null && endLine != null && endLine < startLine) {
            return "Error: end_line ($endLine) must not be less than start_line ($startLine)"
        }

        // -w so that a reformatting commit does not take the blame for lines it only reindented.
        val parameters = mutableListOf("--date=short", "-w")
        if (startLine != null) {
            parameters.add("-L")
            parameters.add("$startLine,${endLine ?: ""}")
        }
        parameters.add("--")
        parameters.add(pathInRepo)

        val result = GitRepositories.run(resolved.repository, GitCommand.BLAME, parameters)
        if (!result.success()) return GitRepositories.errorText(result)

        val blame = result.outputAsJoinedString
        if (blame.isBlank()) return "No blame output -- $path may not be committed yet."
        return GitRepositories.capped(blame, MAX_CHARS, "pass start_line and end_line")
    }
}
