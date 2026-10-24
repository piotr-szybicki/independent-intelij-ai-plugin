package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.github.piotrszybicki.independentintelijaiplugin.agents.AgentWorktreeService
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.HeavyPlatformTestCase
import java.nio.file.Files
import java.nio.file.Path

class ChatWorkingDirectoryTest : HeavyPlatformTestCase() {
    private lateinit var directory: Path

    override fun setUp() {
        super.setUp()
        directory = createTempDir("agent-worktree-integration").toPath()
    }

    private fun diskFile(
        relativePath: String,
        text: String,
    ): VirtualFile {
        val file = directory.resolve(relativePath)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file)!!
    }

    fun testSwitchingRootsKeepsIdenticalRelativePathsIsolated() {
        val original = diskFile("original/same.txt", "original")
        val worktree = diskFile("worktree/same.txt", "agent")
        try {
            ChatWorkingDirectory.select(project, original.parent.path)
            assertEquals(original, PsiTargets.resolveProjectFile(project, "same.txt"))
            ChatWorkingDirectory.select(project, worktree.parent.path)
            assertEquals(worktree, PsiTargets.resolveProjectFile(project, "same.txt"))
            assertEquals("same.txt", PsiTargets.relativePath(project, worktree))
            assertNull(PsiTargets.resolveProjectPath(project, original.path))
            assertNull(PsiTargets.resolveProjectPath(project, "../original/same.txt"))
            ChatWorkingDirectory.select(project, original.parent.path)
            assertEquals(original, PsiTargets.resolveProjectFile(project, "same.txt"))
            ChatWorkingDirectory.select(project, null)
            assertEquals(project.basePath, ChatWorkingDirectory.path(project))
        } finally {
            ChatWorkingDirectory.select(project, null)
        }
    }

    fun testAttachingWorktreePreservesProjectAndDoesNotDuplicateRoots() {
        val target = diskFile("agent-root/README.md", "agent").parent
        val path = Path.of(target.path)
        val countBefore = ModuleManager.getInstance(project).modules.size
        assertSame(project, AgentWorktreeService.openIfNeeded(project, path))
        assertSame(project, AgentWorktreeService.openIfNeeded(project, path))
        assertFalse(project.isDisposed)
        val modules = ModuleManager.getInstance(project).modules
        assertEquals(countBefore + 1, modules.size)
        assertEquals(1, modules.count { target in ModuleRootManager.getInstance(it).contentRoots })
        assertEquals(
            1,
            ProjectLevelVcsManager.getInstance(project).getDirectoryMappings().count {
                it.directory == target.path && it.vcs == "Git"
            },
        )
        val second = diskFile("second-agent/README.md", "another agent").parent
        assertSame(project, AgentWorktreeService.openIfNeeded(project, Path.of(second.path)))
        assertEquals(countBefore + 2, ModuleManager.getInstance(project).modules.size)
    }

    fun testMissingSelectedRootDoesNotFallBackToOriginalFiles() {
        val missing = directory.resolve("deleted")
        assertFalse(Files.exists(missing))
        try {
            ChatWorkingDirectory.select(project, missing.toString())
            assertNull(PsiTargets.resolveProjectFile(project, "README.md"))
            assertNull(AgentWorktreeService.openIfNeeded(project, missing))
        } finally {
            ChatWorkingDirectory.select(project, null)
        }
    }
}
