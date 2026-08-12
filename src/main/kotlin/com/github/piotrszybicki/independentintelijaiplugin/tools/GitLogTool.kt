package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool
import git4idea.commands.GitCommand

/**
 * Commit history, one line each.
 *
 * `--pretty=format` rather than [git4idea.history.GitHistoryUtils], which parses the same output
 * into full commit objects with their changed files: everything past hash, date, author and subject
 * would be spent on tokens the model did not ask for, and git_diff is how it asks for the rest.
 */
class GitLogTool(private val project: Project) : AICodingAgentTool {

    companion object {
        private const val DEFAULT_LIMIT = 20
        private const val MAX_LIMIT = 200
        private const val MAX_CHARS = 20_000

        /** Hash, short date, author, subject -- one line, in that order. */
        private const val FORMAT = "--pretty=format:%h %ad %an: %s"
    }

    override val name = "git_log"
    override val description =
        "Recent commits, newest first: hash, date, author and subject. Optionally for one path, " +
            "or over a revision range."
    override val inputSchema: JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("path", JsonObject().apply {
                addProperty("type", "string")
                addProperty(
                    "description",
                    "File or directory whose history to show, relative to the project root.",
                )
            })
            add("revision_range", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "A range such as main..HEAD, or a single revision to start from.")
            })
            add("limit", JsonObject().apply {
                addProperty("type", "integer")
                addProperty("description", "How many commits to return. Defaults to $DEFAULT_LIMIT, maximum $MAX_LIMIT.")
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

        val limit = (input.get("limit")?.asInt ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val parameters = mutableListOf("--max-count=$limit", "--date=short", FORMAT)
        input.get("revision_range")?.asString?.takeIf { it.isNotBlank() }?.let { parameters.add(it) }
        resolved.pathInRepo?.let { parameters.add("--"); parameters.add(it) }

        val result = GitRepositories.run(resolved.repository, GitCommand.LOG, parameters)
        if (!result.success()) return GitRepositories.errorText(result)

        val log = result.outputAsJoinedString
        if (log.isBlank()) return "No commits found."
        return GitRepositories.capped(log, MAX_CHARS, "lower the limit")
    }
}
