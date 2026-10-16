package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement

interface PsiElementResolver {
    fun resolveElement(
        project: Project,
        relativePath: String,
        line: Int,
        symbolName: String,
    ): PsiElement?

    fun resolveTarget(
        project: Project,
        relativePath: String,
        line: Int,
        symbolName: String,
        occurrence: Int = 1,
    ): PsiElement?

    fun isInProject(
        project: Project,
        element: PsiElement,
    ): Boolean
}
