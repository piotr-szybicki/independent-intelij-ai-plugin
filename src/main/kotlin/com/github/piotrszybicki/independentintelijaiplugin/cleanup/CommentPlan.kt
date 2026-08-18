package com.github.piotrszybicki.independentintelijaiplugin.cleanup

import com.github.piotrszybicki.independentintelijaiplugin.logging.CommentDatabase
import com.intellij.openapi.project.Project

/** A comment [CommentSweep] found, and the file text it sits in. */
data class FoundComment(val fileText: String, val start: Int, val end: Int) {
    val text: String get() = fileText.substring(start, end)
}

/** A span of a file to replace, and what to put there. An empty replacement deletes it. */
data class CommentEdit(val start: Int, val end: Int, val replacement: String)

/**
 * What a sweep does with the comments it finds.
 *
 * Split from [CommentSweep] because the walking, the staleness check and the single undoable write
 * command are the same whether comments are being deleted, stored or put back -- only the two
 * decisions differ: which comments are mine, and what goes in their place.
 *
 * [edit] may do I/O. It is deliberately called outside both the read action and the write command,
 * between the two passes, so a plan that talks to a database does not hold a lock while it waits.
 */
interface CommentPlan {

    /** Names the write command, and so the entry in Undo. */
    val commandName: String

    /** Whether this comment is one to act on, from its text alone. Called under a read action. */
    fun wants(commentText: String): Boolean

    /** The edit for [found], or null to leave it alone after all. May do I/O; may throw. */
    fun edit(found: FoundComment): CommentEdit?
}

/**
 * Takes the documentation out and leaves nothing behind.
 *
 * The whole line goes when the comment had one to itself -- see [JavadocComments.removalFor] -- which
 * is what stops a deleted comment leaving a blank indented gap.
 */
class DeleteJavadoc(private val onlyBlank: Boolean) : CommentPlan {

    override val commandName = "Remove Javadoc Comments"

    override fun wants(commentText: String): Boolean =
        if (onlyBlank) JavadocComments.isBlank(commentText) else JavadocComments.isJavadoc(commentText)

    override fun edit(found: FoundComment): CommentEdit {
        val removal = JavadocComments.removalFor(found.fileText, found.start, found.end)
        return CommentEdit(removal.start, removal.end, "")
    }
}

/**
 * Moves the documentation into the database and leaves a marker naming it.
 *
 * Only the comment itself is replaced, not the line around it, so the marker lands at the
 * indentation the comment had.
 *
 * The row is written before the file is edited, and the two are not one transaction: a sweep that
 * fails part-way leaves rows nothing points at. Harmless -- an unreferenced comment costs a row and
 * is what the id sequence is for -- and the alternative, editing first, would lose the text outright
 * if the insert then failed.
 */
class StoreJavadoc(private val project: Project) : CommentPlan {

    override val commandName = "Move Javadoc Comments to Database"

    /**
     * Doc comments with code beside them, which are left where they are.
     *
     * A marker is a line comment, so putting one where a doc comment sat between code on the same
     * line -- a documented argument, say -- would comment out the rest of that line. Rare enough to
     * skip and report rather than to invent a layout for, and skipping loses nothing: the comment
     * stays where it is, readable in the code.
     */
    var inline: Int = 0
        private set

    /** Markers are line comments, so a file already swept is never swept into itself. */
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

/**
 * Puts stored comments back where their markers are.
 *
 * The stored text carries the indentation its continuation lines had when it was taken out, so a
 * marker still sitting where the comment was restores it exactly. A marker that has since been moved
 * to a different indent gets the comment back with its old one, which is a reformat away from right
 * and never a loss.
 *
 * A marker whose row is gone is left alone rather than blanked: the id in the code is the only
 * remaining evidence of what was there.
 */
class RestoreMarkers(private val project: Project) : CommentPlan {

    override val commandName = "Restore Comments From Database"

    /** Counted separately from the ones restored, so a summary can say a row was missing. */
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
