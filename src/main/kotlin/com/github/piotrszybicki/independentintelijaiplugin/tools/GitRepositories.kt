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

object GitRepositories {

    private const val REFRESH_TIMEOUT_SECONDS = 10L

    sealed class Resolved {
        class Ok(val repository: GitRepository, val pathInRepo: String?) : Resolved()

        class Error(val message: String) : Resolved()
    }

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

        return Resolved.Ok(repository, pathInRepo.ifBlank { null })
    }

    private fun repositoryAtProjectRoot(project: Project, manager: GitRepositoryManager): GitRepository? {
        val baseDir = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        return baseDir?.let { manager.getRepositoryForFile(it) } ?: manager.repositories.singleOrNull()
    }

    fun run(repository: GitRepository, command: GitCommand, parameters: List<String>): GitCommandResult {
        val handler = GitLineHandler(repository.project, repository.root, command)
        handler.addParameters(parameters)
        return Git.getInstance().runCommand(handler)
    }

    fun errorText(result: GitCommandResult): String {
        val detail = result.errorOutputAsJoinedString
            .ifBlank { result.outputAsJoinedString }
            .ifBlank { "no output" }
        return "Error: git exited ${result.exitCode}: ${detail.take(2_000)}"
    }

    fun awaitChangeListUpdate(project: Project): Boolean {
        VcsDirtyScopeManager.getInstance(project).markEverythingDirty()
        val updated = CountDownLatch(1)
        ChangeListManager.getInstance(project).invokeAfterUpdate(true) { updated.countDown() }
        return updated.await(REFRESH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    fun capped(text: String, maxChars: Int, narrowWith: String): String {
        if (text.length <= maxChars) return text
        return text.take(maxChars).substringBeforeLast('\n') +
            "\n\n[TRUNCATED at $maxChars characters -- $narrowWith to see the rest]"
    }
}
