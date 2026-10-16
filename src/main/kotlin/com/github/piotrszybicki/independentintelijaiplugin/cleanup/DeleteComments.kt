package com.github.piotrszybicki.independentintelijaiplugin.cleanup

/**
 * Takes the comments out and leaves nothing behind.
 *
 * How much goes with each one is [JavadocComments.removalFor]'s decision, and it differs by where
 * the comment sits rather than by what kind it is: a comment with its own line takes the line, a
 * trailing one takes the space in front of it, and one with code on both sides takes only itself.
 */
class DeleteComments(
    private val choice: CommentChoice,
) : CommentPlan {
    override val commandName = "Remove Comments"

    override fun wants(commentText: String): Boolean =
        when (choice) {
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
