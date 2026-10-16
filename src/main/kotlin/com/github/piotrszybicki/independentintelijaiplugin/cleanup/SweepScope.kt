package com.github.piotrszybicki.independentintelijaiplugin.cleanup

import com.github.piotrszybicki.independentintelijaiplugin.tools.PsiTargets
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/** Where a sweep runs: the selection when the dialog offered it and the user took it, else the project. */
internal object SweepScope {
    fun roots(
        project: Project,
        selection: List<VirtualFile>,
        useSelection: Boolean,
    ): List<VirtualFile> =
        if (useSelection && selection.isNotEmpty()) {
            selection
        } else {
            listOfNotNull(PsiTargets.resolveProjectFile(project, "."))
        }
}
