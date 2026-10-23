package com.github.piotrszybicki.independentintelijaiplugin.agents

import com.github.piotrszybicki.independentintelijaiplugin.history.StoredChat
import com.github.piotrszybicki.independentintelijaiplugin.tools.GitRepositories
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import git4idea.commands.GitCommand
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Creates isolated agent worktrees without changing the checked-out branch of the parent project. */
object AgentWorktreeService {
    sealed interface Result {
        data class Created(
            val path: Path,
            val branch: String,
            val repositoryRoot: Path,
            val baseCommit: String,
        ) : Result

        data class Failed(
            val message: String,
        ) : Result
    }

    data class Names(
        val branch: String,
        val path: Path,
    )

    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)

    /** Returns whether the project is already opened at [targetPath]. */
    private fun isCurrentWorktree(
        project: Project,
        targetPath: Path,
    ): Boolean {
        val basePath = project.basePath?.let(Path::of) ?: return false
        if (samePath(basePath, targetPath)) return true
        return runCatching {
            val resolved = GitRepositories.resolve(project, null)
            resolved is GitRepositories.Resolved.Ok &&
                samePath(Path.of(resolved.repository.root.path), targetPath)
        }.getOrDefault(false)
    }

    /** Switches the current window to [targetPath], returning its project for agent tools. */
    fun openIfNeeded(
        currentProject: Project,
        targetPath: Path,
    ): Project? {
        if (isCurrentWorktree(currentProject, targetPath)) return currentProject
        return runCatching {
            val open = { AgentWorktreeProjectOpener.open(currentProject, targetPath) }
            if (ApplicationManager.getApplication().isDispatchThread) return@runCatching open()
            var opened: Project? = null
            ApplicationManager.getApplication().invokeAndWait { opened = open() }
            opened
        }.getOrNull()
    }

    /**
     * Collects worktrees recorded by the current chat and each available parent. Invalid or
     * cyclic ancestry is deliberately treated as the end of the chain.
     */
    fun ancestorWorktreePaths(
        currentChatId: String,
        chats: List<StoredChat>,
    ): Set<Path> {
        val byId = chats.associateBy(StoredChat::id)
        val result = linkedSetOf<Path>()
        val visited = mutableSetOf<String>()
        var currentId: String? = currentChatId

        while (currentId != null && visited.add(currentId)) {
            val chat = byId[currentId] ?: break
            chat.agent?.worktreePath?.let { path -> runCatching { result.add(Path.of(path)) } }
            currentId = chat.agent?.parentChatId
        }
        return result
    }

    internal fun samePath(
        first: Path,
        second: Path,
    ): Boolean {
        val left = comparablePath(first)
        val right = comparablePath(second)
        return if (isCaseInsensitiveFileSystem()) {
            left.toString().equals(right.toString(), ignoreCase = true)
        } else {
            left == right
        }
    }

    private fun comparablePath(path: Path): Path =
        runCatching { path.toRealPath() }.getOrElse { path.toAbsolutePath().normalize() }

    private fun isCaseInsensitiveFileSystem(): Boolean =
        System.getProperty("os.name").contains("win", ignoreCase = true)

    fun create(
        project: Project,
        agentName: String,
        handoffId: String,
    ): Result {
        val resolved = GitRepositories.resolve(project, null)
        if (resolved !is GitRepositories.Resolved.Ok) {
            return Result.Failed((resolved as GitRepositories.Resolved.Error).message)
        }
        val repository = resolved.repository
        val repositoryRoot = Path.of(repository.root.path)
        val root =
            project.basePath?.let(Path::of)
                ?: return Result.Failed("the project has no base directory")
        val worktree = names(root, agentName, handoffId)
        if (Files.exists(worktree.path)) return Result.Failed("destination already exists: ${worktree.path}")

        val head = GitRepositories.run(repository, GitCommand.REV_PARSE, listOf("HEAD"))
        if (!head.success()) {
            return Result.Failed(
                "could not determine the current commit: ${GitRepositories.errorText(head)}",
            )
        }
        val baseCommit = head.outputAsJoinedString.trim()
        val created =
            GitRepositories.run(
                repository,
                GitCommand.WORKTREE,
                listOf("add", "-b", worktree.branch, worktree.path.toString(), baseCommit),
            )
        if (!created.success()) return Result.Failed(GitRepositories.errorText(created))
        LocalFileSystem.getInstance().refreshAndFindFileByPath(worktree.path.toString())
        if (!Files.isDirectory(worktree.path) || !Files.exists(worktree.path.resolve(".git"))) {
            return Result.Failed("Git reported success, but the worktree is not a valid project directory")
        }
        return Result.Created(worktree.path, worktree.branch, repositoryRoot, baseCommit)
    }

    private fun names(
        root: Path,
        agentName: String,
        handoffId: String,
    ): Names {
        val safeAgent = sanitize(agentName).ifBlank { "agent" }
        val suffix = sanitize(handoffId).takeLast(12).ifBlank { "task" }
        val time = LocalDateTime.now().format(stamp)
        return Names(
            branch = "agent/$safeAgent-$time-$suffix",
            path = root.parent.resolve("${root.fileName}-worktree-$time-$suffix"),
        )
    }

    private fun sanitize(value: String): String =
        value
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-', '.', '_')
            .take(40)
}
