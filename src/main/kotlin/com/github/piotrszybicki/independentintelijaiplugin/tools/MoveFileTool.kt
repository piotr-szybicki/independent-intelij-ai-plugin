package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesProcessor
import com.intellij.refactoring.rename.RenameProcessor
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool
import java.io.File

/**
 * Moves or renames a file, going through the refactoring engine rather than the file system so
 * imports and references across the project follow it -- the same reason [RenameSymbolTool] exists
 * instead of the model hand-editing every call site.
 *
 * A move and a rename are two separate refactorings, so a call that does both has to pick an order.
 * Whichever runs first leaves the file in an intermediate location, and that location must be free.
 */
class MoveFileTool(private val project: Project) : AICodingAgentTool {

    override val name = "move_file"
    override val description =
        "Moves or renames a project file, creating missing target directories and updating " +
            "references to it. Fails if the target path already exists."
    override val inputSchema: JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("from", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "Current file path relative to the project root, e.g. src/Main.kt")
            })
            add("to", JsonObject().apply {
                addProperty("type", "string")
                addProperty(
                    "description",
                    "New file path relative to the project root, including the file name, e.g. src/app/App.kt",
                )
            })
        })
        add("required", JsonArray().apply { add("from"); add("to") })
    }

    override fun execute(input: JsonObject): String {
        val fromPath = input.get("from")?.asString ?: return "Error: missing 'from'"
        val toPath = input.get("to")?.asString ?: return "Error: missing 'to'"

        val source = PsiTargets.resolveProjectPath(project, fromPath)
            ?: return "Error: path is outside the project directory: $fromPath"
        val target = PsiTargets.resolveProjectPath(project, toPath)
            ?: return "Error: path is outside the project directory: $toPath"

        if (!source.exists()) return "Error: file not found: $fromPath"
        if (source.isDirectory) return "Error: $fromPath is a directory; move its files individually"
        if (target.exists()) return "Error: $toPath already exists"
        if (target.name.isEmpty()) return "Error: 'to' has no file name: $toPath"
        if (source == target) return "Error: 'from' and 'to' are the same file"

        val sourceDir = source.parentFile
        val targetDir = target.parentFile
        val renaming = source.name != target.name
        val moving = sourceDir != targetDir

        // Both intermediate states have to be checked up front: the failure would otherwise land
        // halfway through, with the file renamed but not yet moved.
        val renameFirst = !File(sourceDir, target.name).exists()
        val moveFirst = !File(targetDir, source.name).exists()
        if (renaming && moving && !renameFirst && !moveFirst) {
            return "Error: cannot move and rename in one step -- both $fromPath's name in the target " +
                "directory and ${target.name} in the source directory are taken. Move to a free " +
                "intermediate path first."
        }

        val vf = PsiTargets.resolveProjectFile(project, fromPath)
            ?: return "Error: $fromPath is not loaded in the IDE's file system"

        val targetDirVf = createDirectory(targetDir.path)
            ?: return "Error: cannot create target directory ${targetDir.path}"

        val psiFile = ReadAction.computeBlocking<PsiFile?, RuntimeException> { PsiManager.getInstance(project).findFile(vf) }
        val psiDir = ReadAction.computeBlocking<PsiDirectory?, RuntimeException> {
            PsiManager.getInstance(project).findDirectory(targetDirVf)
        }

        var error: String? = null
        ApplicationManager.getApplication().invokeAndWait {
            try {
                // Binary files and anything else the IDE has no PSI for cannot go through the
                // refactorings; there are no references to update for those anyway.
                if (psiFile == null || psiDir == null) {
                    moveOnDisk(vf, targetDirVf, target.name)
                } else if (renameFirst) {
                    if (renaming) rename(psiFile, target.name)
                    if (moving) move(psiFile, psiDir)
                } else {
                    if (moving) move(psiFile, psiDir)
                    if (renaming) rename(psiFile, target.name)
                }
            } catch (e: Exception) {
                error = "Error moving $fromPath to $toPath: ${e.message}"
            }
        }
        error?.let { return it }

        return "Moved $fromPath to $toPath."
    }

    private fun rename(psiFile: PsiFile, newName: String) {
        RenameProcessor(project, psiFile, newName, false, false).apply { setPreviewUsages(false) }.run()
    }

    private fun move(psiFile: PsiFile, targetDir: PsiDirectory) {
        MoveFilesOrDirectoriesProcessor(project, arrayOf(psiFile), targetDir, false, false, null, null).run()
    }

    private fun moveOnDisk(vf: VirtualFile, targetDir: VirtualFile, newName: String) {
        WriteCommandAction.runWriteCommandAction(project, "Move File", null, Runnable {
            if (vf.parent != targetDir) vf.move(this, targetDir)
            if (vf.name != newName) vf.rename(this, newName)
        })
    }

    private fun createDirectory(path: String): VirtualFile? {
        var created: VirtualFile? = null
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project, "Create Directory", null, Runnable {
                created = runCatching { VfsUtil.createDirectoryIfMissing(path) }.getOrNull()
            })
        }
        return created
    }
}
