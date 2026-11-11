package com.github.piotrszybicki.independentintelijaiplugin.changes

import com.github.piotrszybicki.independentintelijaiplugin.tools.PsiTargets
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ChangeSessionServiceTest : BasePlatformTestCase() {
    fun testDeletedFileCanBeReviewedAndReverted() {
        val file = myFixture.addFileToProject("delete-me.txt", "before").virtualFile
        val parent = file.parent
        val path = PsiTargets.relativePath(project, file)
        val session = ChangeSessionService.getInstance(project)

        WriteCommandAction.runWriteCommandAction(project) {
            session.trackDeletion(file) {
                file.delete(this)
            }
        }

        assertFalse(file.isValid)
        assertEquals(1, session.changedFileCount)
        assertEquals(listOf(file), session.changedFiles())
        assertEquals(listOf(path), session.changedPaths())
        assertTrue(session.isDeleted(file))

        assertEmpty(session.revertAll())

        val restored = parent.findChild("delete-me.txt")
        assertNotNull(restored)
        assertEquals("before", VfsUtilCore.loadText(restored!!))
        assertEquals(0, session.changedFileCount)
    }

    fun testApprovingDeletedFileKeepsItDeleted() {
        val file = myFixture.addFileToProject("approved-deletion.txt", "before").virtualFile
        val session = ChangeSessionService.getInstance(project)

        WriteCommandAction.runWriteCommandAction(project) {
            session.trackDeletion(file) {
                file.delete(this)
            }
        }

        session.approveAll()

        assertFalse(file.isValid)
        assertEquals(0, session.changedFileCount)
        assertEmpty(session.changedFiles())
    }

    fun testPendingEditSurvivesStateReload() {
        val file = myFixture.addFileToProject("pending.txt", "original").virtualFile
        val session = ChangeSessionService.getInstance(project)
        val document =
            com.intellij.openapi.fileEditor.FileDocumentManager
                .getInstance()
                .getDocument(file)!!
        session.beginCapture()
        try {
            WriteCommandAction.runWriteCommandAction(project, "Edit", null, Runnable { document.setText("changed") })
        } finally {
            session.endCapture()
        }
        val saved = session.getState()
        session.approveAll()
        session.loadState(saved)
        assertEquals(1, session.changedFileCount)
        assertEquals("original", session.baselineFor(file))
        assertEmpty(session.revertAll())
        assertEquals("original", document.text)
    }

    fun testPendingDeletionSurvivesStateReload() {
        val file = myFixture.addFileToProject("pending-deletion.txt", "original").virtualFile
        val parent = file.parent
        val session = ChangeSessionService.getInstance(project)
        WriteCommandAction.runWriteCommandAction(project) { session.trackDeletion(file) { file.delete(this) } }
        val saved = session.getState()
        session.approveAll()
        session.loadState(saved)
        assertEquals(1, session.changedFileCount)
        assertEmpty(session.revertAll())
        assertEquals("original", VfsUtilCore.loadText(parent.findChild("pending-deletion.txt")!!))
    }
}
