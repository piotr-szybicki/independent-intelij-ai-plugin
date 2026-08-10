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

/**
 * Finding a class by name anywhere the project can see it, libraries and the SDK included.
 *
 * `find_by_name` deliberately passes `includeNonProjectItems = false`, because "what classes are in
 * this project" should not return forty thousand JDK entries. This is the other question -- "show me
 * *that* class, the one from the dependency" -- where the name is already known and the library is
 * the whole point.
 *
 * Resolution goes through [ChooseByNameContributor], the same Go to Class extension point, so it
 * works for whatever languages the IDE has rather than only for Java. Crucially it asks
 * `getItemsByName` directly instead of enumerating `getNames`: with non-project items included, the
 * name list runs to hundreds of thousands of entries, and we already know what we are looking for.
 */
internal object LibraryClasses {

    class Candidate(
        val qualifiedName: String,
        val file: PsiFile,
        val virtualFile: VirtualFile,
        /** True for a class in the project's own sources, which belongs to `read_project_file`. */
        val inProject: Boolean,
        /**
         * True when what we resolved to is real source rather than a class file. False means the
         * library ships no sources jar, or ships one nobody attached, and reading it will decompile.
         */
        val fromSources: Boolean,
        /** The library it came from, for telling the user what to attach sources for. */
        val libraryName: String?,
    )

    sealed class Resolution {
        class Found(val candidate: Candidate) : Resolution()
        class Ambiguous(val candidates: List<Candidate>) : Resolution()
        object NotFound : Resolution()
    }

    /**
     * Resolves [name], which may be a simple name (`RenameProcessor`) or a qualified one
     * (`com.intellij.refactoring.rename.RenameProcessor`). A qualifier narrows the result; without
     * one, several libraries may legitimately offer the same simple name.
     */
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

    /** The name of the library [vf] came from, e.g. `Gradle: org.commonmark:commonmark:0.29.0`. */
    private fun libraryNameOf(index: ProjectFileIndex, vf: VirtualFile): String? =
        index.getOrderEntriesForFile(vf)
            .filterIsInstance<LibraryOrderEntry>()
            .firstNotNullOfOrNull { it.libraryName }
}
