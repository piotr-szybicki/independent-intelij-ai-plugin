package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.dvcs.repo.Repository
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VirtualFile
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool
import git4idea.repo.GitRepository

class GitStatusTool(private val project: Project) : AICodingAgentTool {

    companion object {
        private const val MAX_ENTRIES = 300
    }

    override val name = "git_status"
    override val description =
        "Uncommitted changes and untracked files, with the current branch. Paths are relative to " +
            "the project root."
    override val inputSchema: JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("path", JsonObject().apply {
                addProperty("type", "string")
                addProperty(
                    "description",
                    "Any path in the repository to report on. Only needed when the project holds more than one.",
                )
            })
        })
        add("required", JsonArray())
    }

    override fun execute(input: JsonObject): String {
        val resolved = when (val result = GitRepositories.resolve(project, input.get("path")?.asString)) {
            is GitRepositories.Resolved.Error -> return result.message
            is GitRepositories.Resolved.Ok -> result
        }
        val repository = resolved.repository
        val root = repository.root

        val current = GitRepositories.awaitChangeListUpdate(project)
        val changeListManager = ChangeListManager.getInstance(project)

        val entries = mutableListOf<String>()
        changeListManager.allChanges.mapNotNullTo(entries) { describe(it, root) }
        changeListManager.unversionedFilesPaths
            .filter { isUnder(it, root) }
            .mapTo(entries) { "untracked ${relativePath(it)}" }
        entries.sortBy { it.substringAfter(' ').trim() }

        return buildString {
            append(header(repository))
            if (!current) append(" (the IDE was still refreshing; this may be a moment out of date)")
            append('\n')

            if (entries.isEmpty()) {
                append("Working tree clean.")
                return@buildString
            }
            entries.take(MAX_ENTRIES).forEach { append(it).append('\n') }
            if (entries.size > MAX_ENTRIES) append("[${entries.size - MAX_ENTRIES} more, not shown]")
        }
    }

    private fun header(repository: GitRepository): String {
        val branch = repository.currentBranchName ?: "a detached HEAD"
        val revision = repository.currentRevision?.take(7)?.let { " at $it" }.orEmpty()
        val state = repository.state.takeIf { it != Repository.State.NORMAL }
            ?.let { ", ${it.name.lowercase()} in progress" }
            .orEmpty()
        return "On $branch$revision$state"
    }

    private fun describe(change: Change, root: VirtualFile): String? {
        val before = change.beforeRevision?.file
        val after = change.afterRevision?.file
        val subject = after ?: before ?: return null
        if (!isUnder(subject, root)) return null

        val path = relativePath(subject)
        return when (change.type) {
            Change.Type.NEW -> "added     $path"
            Change.Type.DELETED -> "deleted   $path"
            Change.Type.MOVED -> "moved     ${before?.let { relativePath(it) }} -> $path"
            Change.Type.MODIFICATION -> "modified  $path"
        }
    }

    private fun isUnder(path: FilePath, root: VirtualFile): Boolean =
        path.path == root.path || path.path.startsWith(root.path + "/")

    private fun relativePath(path: FilePath): String {
        val basePath = project.basePath ?: return path.path
        return if (path.path.startsWith(basePath)) {
            path.path.removePrefix(basePath).trimStart('/', '\\')
        } else {
            path.path
        }
    }
}
