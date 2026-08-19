package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.github.piotrszybicki.independentintelijaiplugin.tools.DirectoryListing.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectoryListingTest {

    private val sourceTree = listOf(
        Entry("com", true),
        Entry("com/example", true),
        Entry("com/example/A.kt", false),
        Entry("com/example/B.kt", false),
        Entry("README.md", false),
    )

    @Test
    fun `prints each directory once instead of once per file`() {
        assertEquals(
            """
            src/: 2 director(y/ies), 3 file(s)
            README.md
            com/example/
              A.kt, B.kt

            """.trimIndent(),
            DirectoryListing.format("src", sourceTree, truncated = false),
        )
    }

    @Test
    fun `collapses a chain of single-subdirectory directories onto one line`() {
        val listing = DirectoryListing.format(
            "src",
            listOf(
                Entry("com", true),
                Entry("com/github", true),
                Entry("com/github/vendor", true),
                Entry("com/github/vendor/Tool.kt", false),
            ),
            truncated = false,
        )

        assertTrue(listing, listing.contains("com/github/vendor/\n  Tool.kt"))
    }

    @Test
    fun `keeps a directory that has files of its own`() {
        val listing = DirectoryListing.format(
            "src",
            listOf(
                Entry("com", true),
                Entry("com/example", true),
                Entry("com/example/A.kt", false),
                Entry("com/Root.kt", false),
            ),
            truncated = false,
        )

        assertEquals(
            """
            src/: 2 director(y/ies), 2 file(s)
            com/
              Root.kt
              example/
                A.kt

            """.trimIndent(),
            listing,
        )
    }

    @Test
    fun `keeps a directory with nothing in it`() {
        val listing = DirectoryListing.format(
            "src",
            listOf(Entry("generated", true), Entry("Main.kt", false)),
            truncated = false,
        )

        assertTrue(listing, listing.contains("generated/"))
    }

    @Test
    fun `says the walk stopped at the limit rather than running out of files`() {
        val listing = DirectoryListing.format("src", sourceTree, truncated = true)

        assertTrue(listing, listing.lineSequence().first().contains("stopped at the limit"))
    }

    @Test
    fun `reports an empty directory as empty`() {
        assertEquals("src/ is empty.", DirectoryListing.format("src", emptyList(), truncated = false))
    }

    @Test
    fun `the tree says a deep package once rather than once per file`() {
        assertEquals(
            """
            com/example/deep/
              A.kt, B.kt, C.kt

            """.trimIndent(),
            DirectoryListing.tree(
                listOf(
                    "com/example/deep/A.kt",
                    "com/example/deep/B.kt",
                    "com/example/deep/C.kt",
                ),
            ),
        )
    }

    @Test
    fun `the tree keeps files that sit in the project root`() {
        assertEquals(
            """
            build.gradle.kts
            src/main/
              Main.kt

            """.trimIndent(),
            DirectoryListing.tree(listOf("build.gradle.kts", "src/main/Main.kt")),
        )
    }

    @Test
    fun `the tree lists a repeated path once`() {
        assertEquals(
            "src/\n  A.kt\n",
            DirectoryListing.tree(listOf("src/A.kt", "src/A.kt")),
        )
    }

    @Test
    fun `the tree of nothing is nothing`() {
        assertEquals("", DirectoryListing.tree(emptyList()))
    }
}
