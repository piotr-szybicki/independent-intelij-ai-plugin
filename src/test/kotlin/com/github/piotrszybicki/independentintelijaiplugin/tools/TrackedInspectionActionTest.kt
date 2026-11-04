package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.intellij.analysis.AnalysisScope
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.xml.XmlTag
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class TrackedInspectionActionTest : BasePlatformTestCase() {
    fun testWaitsForInspectionAndReturnsItsFinding() {
        myFixture.enableInspections(TestInspection())
        val file = myFixture.addFileToProject("tracked.xml", "<root>\n  <bad/>\n</root>")
        val outcome = CompletableFuture<String>()
        val action = TrackedInspectionAction(outcome)
        action.runInspections(project, AnalysisScope(file))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (!outcome.isDone && System.nanoTime() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(20)
        }
        assertTrue("Inspection did not finish", outcome.isDone)
        val result = outcome.get()
        assertTrue(result, result.contains("tracked.xml:2"))
        assertTrue(result, result.contains("Test inspection finding"))
    }

    class TestInspection : LocalInspectionTool() {
        override fun getDisplayName() = "Test inspection"

        override fun getGroupDisplayName() = "Test inspections"

        override fun buildVisitor(
            holder: ProblemsHolder,
            isOnTheFly: Boolean,
        ): PsiElementVisitor =
            object : PsiElementVisitor() {
                override fun visitElement(element: PsiElement) {
                    if (element is XmlTag && element.name == "bad") {
                        holder.registerProblem(element, "Test inspection finding")
                    }
                }
            }
    }
}
