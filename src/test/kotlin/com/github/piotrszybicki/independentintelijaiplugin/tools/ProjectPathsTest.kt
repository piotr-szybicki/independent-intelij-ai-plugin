package com.github.piotrszybicki.independentintelijaiplugin.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectPathsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `accepts absolute project root with either separator style`() {
        val base = temporaryFolder.newFolder("project").canonicalFile
        assertEquals(base, ProjectPaths.resolve(base, base.path))
        assertEquals(base, ProjectPaths.resolve(base, base.path.replace('\\', '/')))
    }

    @Test
    fun `accepts relative and absolute paths inside project`() {
        val base = temporaryFolder.newFolder("project").canonicalFile
        val child = File(base, "src").apply { mkdir() }
        assertEquals(base, ProjectPaths.resolve(base, "."))
        assertEquals(child, ProjectPaths.resolve(base, "src"))
        assertEquals(child, ProjectPaths.resolve(base, child.absolutePath))
        assertEquals(child, ProjectPaths.resolve(base, "src/../src"))
    }

    @Test
    fun `rejects traversal and absolute paths outside project including shared prefixes`() {
        val base = temporaryFolder.newFolder("project").canonicalFile
        val sibling = temporaryFolder.newFolder("project-other")
        assertNull(ProjectPaths.resolve(base, ".."))
        assertNull(ProjectPaths.resolve(base, "../project-other"))
        assertNull(ProjectPaths.resolve(base, sibling.absolutePath))
    }
}
