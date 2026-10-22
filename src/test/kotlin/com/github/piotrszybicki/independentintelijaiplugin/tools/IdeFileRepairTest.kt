package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.google.gson.JsonObject
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class IdeFileRepairTest : BasePlatformTestCase() {
    fun testRepairLoopReanalysesUnsavedFileWithRealIdeDiagnostics() {
        val vf = myFixture.addFileToProject("repair.xml", "<root><broken></root>").virtualFile
        val document = FileDocumentManager.getInstance().getDocument(vf)!!
        val diagnostics = GetFileProblemsTool(project) { vf }
        val input = JsonObject().apply { addProperty("path", "repair.xml") }
        var requests = 0
        val result =
            FileRepairLoop(
                read = { document.text },
                analyse = { diagnostics.execute(input) },
                repair = { content, problems ->
                    requests++
                    assertEquals("<root><broken></root>", content)
                    assertTrue(problems, problems.contains("problem(s)"))
                    "<root><fixed/></root>"
                },
                replace = { original, replacement ->
                    assertEquals(original, document.text)
                    WriteCommandAction.runWriteCommandAction(project) {
                        document.setText(replacement)
                        PsiDocumentManager.getInstance(project).commitDocument(document)
                    }
                },
                isCancelled = { false },
            ).run()
        assertEquals("Problems were detected and fixed", result)
        assertEquals(1, requests)
        assertTrue(diagnostics.execute(input).startsWith("No problems at severity "))

        WriteCommandAction.runWriteCommandAction(project) {
            document.setText("<root><broken></root>")
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        assertTrue(diagnostics.execute(input).contains("problem(s)"))
    }
}
