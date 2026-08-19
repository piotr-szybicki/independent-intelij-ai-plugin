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

class DeleteJavadoc(private val onlyBlank: Boolean) : CommentPlan {

    override val commandName = "Remove Javadoc Comments"

    override fun wants(commentText: String): Boolean =
        if (onlyBlank) JavadocComments.isBlank(commentText) else JavadocComments.isJavadoc(commentText)

    override fun edit(found: FoundComment): CommentEdit {
        val removal = JavadocComments.removalFor(found.fileText, found.start, found.end)
        return CommentEdit(removal.start, removal.end, "")
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
