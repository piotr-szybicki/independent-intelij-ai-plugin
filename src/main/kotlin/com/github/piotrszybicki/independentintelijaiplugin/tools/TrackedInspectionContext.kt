package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.intellij.analysis.AnalysisScope
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemDescriptorUtil
import com.intellij.codeInspection.ex.GlobalInspectionContextImpl
import com.intellij.codeInspection.ex.InspectionManagerEx
import com.intellij.openapi.project.Project

internal class TrackedInspectionContext(
    project: Project,
    manager: InspectionManagerEx,
) : GlobalInspectionContextImpl(project, manager.contentManager) {
    fun prepare() {
        myViewClosed = false
    }

    fun showResults(scope: AnalysisScope) {
        notifyInspectionsFinished(scope)
    }

    fun findings(): String {
        val results = linkedSetOf<String>()
        var omitted = 0
        var size = 0
        for (tools in usedTools) {
            for (state in tools.tools) {
                val wrapper = state.tool
                for (descriptor in getPresentation(wrapper).problemDescriptors) {
                    val problem = descriptor as? ProblemDescriptor
                    val element = problem?.psiElement
                    val path = element?.containingFile?.virtualFile?.path
                    val line = problem?.lineNumber?.takeIf { it >= 0 }?.plus(1)
                    val location = path?.let { if (line != null) "$it:$line: " else "$it: " }.orEmpty()
                    val message = ProblemDescriptorUtil.renderDescriptionMessage(descriptor, element)
                    val finding = "$location${wrapper.displayName}: $message".take(MAX_FINDING_CHARS)
                    if (finding in results) continue
                    if (results.size >= MAX_FINDINGS || size + finding.length > MAX_OUTPUT_CHARS) {
                        omitted++
                    } else {
                        results.add(finding)
                        size += finding.length
                    }
                }
            }
        }
        if (results.isEmpty()) return "Code inspection completed. No findings were reported."
        return buildString {
            append("Code inspection completed. Findings:\n\n")
            append(results.joinToString("\n"))
            if (omitted > 0) append("\n\n$omitted additional finding(s) omitted; see the Inspection Results window.")
        }
    }

    private companion object {
        const val MAX_FINDINGS = 100
        const val MAX_OUTPUT_CHARS = 24_000
        const val MAX_FINDING_CHARS = 4_000
    }
}
