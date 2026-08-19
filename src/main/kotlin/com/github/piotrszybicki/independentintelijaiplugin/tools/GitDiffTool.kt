package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool
import git4idea.commands.GitCommand

class GitDiffTool(private val project: Project) : AICodingAgentTool {

    companion object {
        private const val MAX_CHARS = 30_000
    }

    override val name = "git_diff"
    override val description =
        "Unified diff. Uncommitted changes by default; 'from' alone diffs that revision against " +
            "the working tree, 'from' and 'to' diff two revisions. Optionally limited to one path."
    override val inputSchema: JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("path", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "File or directory to limit the diff to, relative to the project root.")
            })
            add("from", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "Revision to diff from -- a hash, branch or tag. Defaults to HEAD.")
            })
            add("to", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "Revision to diff to. Defaults to the working tree.")
            })
        })
        add("required", JsonArray())
    }

    override fun execute(input: JsonObject): String {
        val path = input.get("path")?.asString
        val resolved = when (val result = GitRepositories.resolve(project, path)) {
            is GitRepositories.Resolved.Error -> return result.message
            is GitRepositories.Resolved.Ok -> result
        }

        val from = input.get("from")?.asString?.takeIf { it.isNotBlank() }
        val to = input.get("to")?.asString?.takeIf { it.isNotBlank() }
        if (to != null && from == null) return "Error: 'to' needs a 'from' to diff against"

        val parameters = mutableListOf<String>()
        // Against HEAD rather than the index, so staged and unstaged changes come back together --
        // git_status does not distinguish them either.
        parameters.add(from ?: "HEAD")
        to?.let { parameters.add(it) }
        resolved.pathInRepo?.let { parameters.add("--"); parameters.add(it) }

        val result = GitRepositories.run(resolved.repository, GitCommand.DIFF, parameters)
        if (!result.success()) return GitRepositories.errorText(result)

        val diff = result.outputAsJoinedString
        if (diff.isBlank()) return "No differences."
        return GitRepositories.capped(diff, MAX_CHARS, "pass a narrower path")
    }
}
