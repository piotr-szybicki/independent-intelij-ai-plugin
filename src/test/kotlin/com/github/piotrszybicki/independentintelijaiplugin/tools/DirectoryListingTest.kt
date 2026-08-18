package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.github.piotrszybicki.independentintelijaiplugin.tools.DirectoryListing.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape of a directory listing the model reads.
 *
 * What is worth pinning is not that the tree is pretty but that it still says everything the flat
 * listing said: which names are directories, what each directory holds, and that a walk stopped
 * early. A format that saves tokens by quietly dropping one of those is worse than the long one.
 */
class DirectoryListingTest {

    /** Walk order: a directory, then its subtree, then the parent's own files. */
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

    /** The package prefix is four lines of nothing when each level gets its own. */
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

    /** A directory holding files as well as a subdirectory has nothing to collapse into. */
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

    /** An empty directory is a leaf with no line under it -- it must not vanish from the tree. */
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

    // What find_by_name's file results are rendered through. The prefix is the whole point: a search
    // for ".kt" matches every file in a package, and a path per line is that package's name once per
    // file -- the bulk of the answer and none of the information.
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

    // The walk can reach one file by two routes, and the same name twice reads as two files.
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
