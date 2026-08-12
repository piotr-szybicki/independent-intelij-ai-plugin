package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitCommandResult
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Everything the git tools know about git4idea, in one place.
 *
 * Two reasons for the funnel. git4idea is a bundled *plugin*, not platform API, and JetBrains
 * promises nothing about its stability -- keeping the surface we touch in one file makes the next
 * release's reshuffle one file's worth of fixing. And running through [Git] rather than spawning a
 * process of our own means the git executable the IDE is configured with, on the IDE's own
 * credential and progress machinery, with the exit code and output handed back as data instead of
 * as terminal text that has to be scraped -- which is the whole reason these tools beat asking
 * run_shell_command to do the same job.
 *
 * Commands are deliberately not silenced: they show up in the Git console like any other, so the
 * user can see what the model ran without the tools having to say so.
 */
object GitRepositories {

    /** How long a status refresh may hold the agent thread before we report what we have. */
    private const val REFRESH_TIMEOUT_SECONDS = 10L

    /** Where a call landed: a repository, and the path inside it the caller named, if any. */
    sealed class Resolved {
        class Ok(val repository: GitRepository, val pathInRepo: String?) : Resolved()

        /** Already worded for the model, in the "Error: ..." form the other tools use. */
        class Error(val message: String) : Resolved()
    }

    /**
     * The repository a call is about.
     *
     * With no path, the repository at the project root -- or the only one, if the project root is
     * not itself a repository. Naming a path is how the model reaches the others in a multi-repo
     * project, so the ambiguous case says so rather than guessing.
     */
    fun resolve(project: Project, relativePath: String?): Resolved {
        val manager = GitRepositoryManager.getInstance(project)
        if (manager.repositories.isEmpty()) {
            return Resolved.Error("Error: this project is not under Git -- no repository found")
        }

        if (relativePath.isNullOrBlank()) {
            val repository = repositoryAtProjectRoot(project, manager)
                ?: return Resolved.Error(
                    "Error: this project has several Git repositories and none at its root. Pass a " +
                        "path in the one you mean: " +
                        manager.repositories.joinToString(", ") { PsiTargets.relativePath(project, it.root) },
                )
            return Resolved.Ok(repository, null)
        }

        val file = PsiTargets.resolveProjectFile(project, relativePath)
            ?: return Resolved.Error(
                "Error: path is outside the project directory or does not exist: $relativePath",
            )
        val repository = manager.getRepositoryForFile(file)
            ?: return Resolved.Error("Error: $relativePath is not inside a Git repository")
        val pathInRepo = VfsUtilCore.getRelativePath(file, repository.root)
            ?: return Resolved.Error("Error: could not locate $relativePath inside ${repository.root.name}")

        // The repository root itself relativises to "", which as a git pathspec matches nothing --
        // it means the same as naming no path at all, so say that instead.
        return Resolved.Ok(repository, pathInRepo.ifBlank { null })
    }

    private fun repositoryAtProjectRoot(project: Project, manager: GitRepositoryManager): GitRepository? {
        val baseDir = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        return baseDir?.let { manager.getRepositoryForFile(it) } ?: manager.repositories.singleOrNull()
    }

    /** Runs a git command in [repository] and waits for it. Must not be called on the EDT. */
    fun run(repository: GitRepository, command: GitCommand, parameters: List<String>): GitCommandResult {
        val handler = GitLineHandler(repository.project, repository.root, command)
        handler.addParameters(parameters)
        return Git.getInstance().runCommand(handler)
    }

    /** What to hand back when a command failed, in the "Error: ..." form the other tools use. */
    fun errorText(result: GitCommandResult): String {
        val detail = result.errorOutputAsJoinedString
            .ifBlank { result.outputAsJoinedString }
            .ifBlank { "no output" }
        return "Error: git exited ${result.exitCode}: ${detail.take(2_000)}"
    }

    /**
     * Blocks until the IDE's view of what has changed has caught up with the disk.
     *
     * Tool edits reach disk as they are made, but the change list is updated asynchronously off the
     * resulting VFS events, so a status read straight after an edit would otherwise report the
     * state from before it. Bounded rather than indefinite: a stale answer beats a hung turn, and
     * the caller says which it got.
     */
    fun awaitChangeListUpdate(project: Project): Boolean {
        VcsDirtyScopeManager.getInstance(project).markEverythingDirty()
        val updated = CountDownLatch(1)
        ChangeListManager.getInstance(project).invokeAfterUpdate(true) { updated.countDown() }
        return updated.await(REFRESH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    /**
     * Caps command output, keeping the head.
     *
     * The opposite of run_shell_command's tail: a diff or a log reads from the top, and the part
     * worth keeping when there is too much is the beginning.
     */
    fun capped(text: String, maxChars: Int, narrowWith: String): String {
        if (text.length <= maxChars) return text
        return text.take(maxChars).substringBeforeLast('\n') +
            "\n\n[TRUNCATED at $maxChars characters -- $narrowWith to see the rest]"
    }
}
