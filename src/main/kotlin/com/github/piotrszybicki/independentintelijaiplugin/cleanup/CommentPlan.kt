package com.github.piotrszybicki.independentintelijaiplugin.cleanup

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
 */
class DeleteComments(private val choice: CommentChoice) : CommentPlan {

    override val commandName = "Remove Comments"

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

    private fun isLine(text: String): Boolean = text.startsWith("//")
}
