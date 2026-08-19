package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.ElementDescriptionUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.usageView.UsageViewLongNameLocation

internal object LibraryClasses {

    class Candidate(
        val qualifiedName: String,
        val file: PsiFile,
        val virtualFile: VirtualFile,
        val inProject: Boolean,
        val fromSources: Boolean,
        val libraryName: String?,
    )

    sealed class Resolution {
        class Found(val candidate: Candidate) : Resolution()
        class Ambiguous(val candidates: List<Candidate>) : Resolution()
        object NotFound : Resolution()
    }

    fun resolve(project: Project, name: String, maxCandidates: Int): Resolution {
        val trimmed = name.trim().removeSuffix(".class").removeSuffix(".java").removeSuffix(".kt")
        if (trimmed.isEmpty()) return Resolution.NotFound

        val simpleName = trimmed.substringAfterLast('.')
        val qualified = trimmed.contains('.')

        val index = ProjectFileIndex.getInstance(project)
        val byPath = LinkedHashMap<String, Candidate>()

        for (contributor in ChooseByNameContributor.CLASS_EP_NAME.extensionList) {
            // One unhealthy contributor -- a language plugin mid-reload, an index still building --
            // must not take the search down; another may hold the answer.
            val items = runCatching {
                ReadAction.computeBlocking<Array<NavigationItem>, RuntimeException> {
                    contributor.getItemsByName(simpleName, simpleName, project, true)
                }
            }.getOrDefault(emptyArray())

            for (item in items) {
                val candidate = describe(project, index, item) ?: continue
                if (qualified && !matchesQualifier(candidate.qualifiedName, trimmed)) continue
                byPath.putIfAbsent(candidate.virtualFile.path, candidate)
                if (byPath.size >= maxCandidates) break
            }
        }

        val candidates = byPath.values.toList()
        return when {
            candidates.isEmpty() -> Resolution.NotFound
            candidates.size == 1 -> Resolution.Found(candidates.single())
            // An exact qualified hit beats the rest outright rather than being reported as ambiguous.
            else -> candidates.firstOrNull { it.qualifiedName == trimmed }
                ?.let { Resolution.Found(it) }
                ?: Resolution.Ambiguous(candidates)
        }
    }

    private fun matchesQualifier(qualifiedName: String, requested: String): Boolean =
        qualifiedName == requested || qualifiedName.endsWith(".$requested")

    private fun describe(project: Project, index: ProjectFileIndex, item: NavigationItem): Candidate? =
        ReadAction.computeBlocking<Candidate?, RuntimeException> {
            val element = item as? PsiElement ?: return@computeBlocking null

            // The index holds the .class file even for a library whose sources are attached, so
            // resolving straight through `containingFile` would decompile source we already have.
            // The navigation element is where Go to Declaration would land: the real .java or .kt.
            val target = element.navigationElement ?: element
            val file = target.containingFile ?: element.containingFile ?: return@computeBlocking null
            val vf = file.virtualFile ?: return@computeBlocking null

            // The boundary that keeps this from becoming "read any file on disk": the file has to be
            // somewhere the project model already knows about.
            val inProject = PsiTargets.isInProject(project, element)
            if (!inProject && !index.isInLibrary(vf)) return@computeBlocking null

            val qualifiedName = ElementDescriptionUtil
                .getElementDescription(element, UsageViewLongNameLocation.INSTANCE)
                .ifBlank { item.name.orEmpty() }

            Candidate(
                qualifiedName = qualifiedName,
                file = file,
                virtualFile = vf,
                inProject = inProject,
                fromSources = inProject || index.isInLibrarySource(vf),
                libraryName = libraryNameOf(index, vf),
            )
        }

    private fun libraryNameOf(index: ProjectFileIndex, vf: VirtualFile): String? =
        index.getOrderEntriesForFile(vf)
            .filterIsInstance<LibraryOrderEntry>()
            .firstNotNullOfOrNull { it.libraryName }
}
