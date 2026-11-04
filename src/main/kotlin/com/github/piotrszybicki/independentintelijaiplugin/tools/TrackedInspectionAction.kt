package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.intellij.analysis.AnalysisScope
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.actions.CodeInspectionAction
import com.intellij.codeInspection.ex.InspectionManagerEx
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiDocumentManager
import java.util.concurrent.CompletableFuture

/** Uses the normal Inspect Code dialog, but owns the resulting inspection task and its callbacks. */
internal class TrackedInspectionAction(
    private val outcome: CompletableFuture<String>,
) : CodeInspectionAction() {
    var started = false
        private set

    override fun canceled() {
        super.canceled()
        outcome.complete("Code inspection was cancelled before it started.")
    }

    public override fun runInspections(
        project: Project,
        scope: AnalysisScope,
    ) {
        started = true
        val lifetime = Disposer.newDisposable("AI code inspection")
        Disposer.register(project, lifetime)
        Disposer.register(lifetime) { outcome.complete("Code inspection stopped because the project closed.") }
        outcome.whenComplete { _, _ -> Disposer.dispose(lifetime) }

        val manager = InspectionManager.getInstance(project) as InspectionManagerEx
        val context = TrackedInspectionContext(project, manager)
        context.setExternalProfile(myExternalProfile)
        context.currentScope = scope
        scope.setSearchInLibraries(false)

        DumbService.getInstance(project).runWhenSmart {
            if (project.isDisposed || outcome.isDone) return@runWhenSmart
            try {
                PsiDocumentManager.getInstance(project).commitAllDocuments()
                context.prepare()
                object : Task.Backgroundable(project, "Inspect Code", true) {
                    private var findings = ""

                    override fun run(indicator: ProgressIndicator) {
                        context.performInspectionsWithProgress(scope, false, false)
                        findings = ReadAction.compute<String, RuntimeException> { context.findings() }
                    }

                    override fun onSuccess() {
                        try {
                            context.showResults(scope)
                        } finally {
                            outcome.complete(findings)
                        }
                    }

                    override fun onCancel() {
                        try {
                            context.cleanup()
                        } finally {
                            outcome.complete("Code inspection was cancelled.")
                        }
                    }

                    override fun onThrowable(error: Throwable) {
                        try {
                            context.cleanup()
                        } finally {
                            outcome.complete("Code inspection failed: ${error.message ?: error::class.java.simpleName}")
                        }
                    }
                }.queue()
            } catch (error: Exception) {
                outcome.complete("Code inspection could not start: ${error.message ?: error::class.java.simpleName}")
            }
        }
    }
}
