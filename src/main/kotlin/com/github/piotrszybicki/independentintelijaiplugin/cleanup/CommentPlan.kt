package com.github.piotrszybicki.independentintelijaiplugin.cleanup

import com.github.piotrszybicki.independentintelijaiplugin.logging.CommentDatabase
import com.intellij.openapi.project.Project

data class FoundComment(val fileText: String, val start: Int, val end: Int) {
    val text: String get() = fileText.substring(start, end)
}

data class CommentEdit(val start: Int, val end: Int, val replacement: String)

interface CommentPlan {

    val commandName: String

    fun wants(commentText: String): Boolean

    fun edit(found: FoundComment): CommentEdit?
}

/** Which comments a removal sweep takes. */
enum class CommentChoice {
    /** Every Javadoc-form comment. */
    JAVADOC,

    /** Only the empty Javadoc stubs -- see [JavadocComments.isBlank]. */
    EMPTY_JAVADOC,

    /** Every line comment. */
    LINE,

    /** Both forms at once. */
    JAVADOC_AND_LINE,
}

/**
 * Takes the comments out and leaves nothing behind.
 *
 * How much goes with each one is [JavadocComments.removalFor]'s decision, and it differs by where
 * the comment sits rather than by what kind it is: a comment with its own line takes the line, a
 * trailing one takes the space in front of it, and one with code on both sides takes only itself.
 *
 * `comment_id` markers are the exception, and [includeMarkers] is off by default because of what
 * they are: a marker is not a comment so much as the only pointer to one. Deleting it does not lose
 * a line of text, it loses the paragraph in the database that line was standing in for, with nothing
 * left in the code to say the row was ever referenced.
 */
class DeleteComments(
    private val choice: CommentChoice,
    private val includeMarkers: Boolean = false,
) : CommentPlan {

    override val commandName = "Remove Comments"

    /** Markers passed over because [includeMarkers] is off, so a summary can say they were spared. */
    var markersKept: Int = 0
        private set

    override fun wants(commentText: String): Boolean = when (choice) {
        CommentChoice.JAVADOC -> JavadocComments.isJavadoc(commentText)
        CommentChoice.EMPTY_JAVADOC -> JavadocComments.isBlank(commentText)
        CommentChoice.LINE -> isLine(commentText)
        CommentChoice.JAVADOC_AND_LINE -> JavadocComments.isJavadoc(commentText) || isLine(commentText)
    }

    override fun edit(found: FoundComment): CommentEdit {
        val removal = JavadocComments.removalFor(found.fileText, found.start, found.end)
        return CommentEdit(removal.start, removal.end, "")
    }

    private fun isLine(text: String): Boolean {
        if (!text.startsWith("//")) return false
        if (includeMarkers || CommentMarker.idIn(text) == null) return true
        markersKept++
        return false
    }
}

class StoreJavadoc(private val project: Project) : CommentPlan {

    override val commandName = "Move Javadoc Comments to Database"

    var inline: Int = 0
        private set

    override fun wants(commentText: String): Boolean = JavadocComments.isJavadoc(commentText)

    override fun edit(found: FoundComment): CommentEdit? {
        if (!JavadocComments.isAlone(found.fileText, found.start, found.end)) {
            inline++
            return null
        }
        val id = CommentDatabase.insert(project, found.text)
        return CommentEdit(found.start, found.end, CommentMarker.of(id))
    }
}

class RestoreMarkers(private val project: Project) : CommentPlan {

    override val commandName = "Restore Comments From Database"

    var missing: Int = 0
        private set

    override fun wants(commentText: String): Boolean = CommentMarker.idIn(commentText) != null

    override fun edit(found: FoundComment): CommentEdit? {
        val id = CommentMarker.idIn(found.text) ?: return null
        val stored = CommentDatabase.read(project, id)
        if (stored == null) {
            missing++
            return null
        }
        return CommentEdit(found.start, found.end, stored)
    }
}
