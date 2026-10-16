package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import java.io.File

interface PsiPathResolver {
    fun resolveProjectPath(
        project: Project,
        relativePath: String,
    ): File?

    fun resolveProjectFile(
        project: Project,
        relativePath: String,
    ): VirtualFile?

    fun resolvePsiFile(
        project: Project,
        relativePath: String,
    ): PsiFile?

    fun relativePath(
        project: Project,
        vf: VirtualFile,
    ): String
}
