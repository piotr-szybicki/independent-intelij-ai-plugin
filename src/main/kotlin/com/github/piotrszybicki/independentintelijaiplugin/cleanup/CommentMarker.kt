package com.github.piotrszybicki.independentintelijaiplugin.cleanup

/**
 * The line a stored comment leaves behind: `// comment_id: 42`.
 *
 * A line comment rather than a doc comment, for two reasons. It has to still be valid code where a
 * doc-comment block stood, and it must not be picked up by the next sweep -- a marker in doc-comment
 * form would be stored as a comment in its own right, and the file would end up holding an id
 * pointing at a row holding an id. It is also the form the model is told it may write directly.
 */
object CommentMarker {

    private val PATTERN = Regex("""^//\s*comment_id:\s*(\d+)\s*$""")

    /** What goes in the code where the comment was. */
    fun of(id: Long): String = "// comment_id: $id"

    /**
     * The id a marker names, or null when [commentText] is an ordinary line comment.
     *
     * Whole-text rather than a search, so a line comment that merely mentions a marker -- in prose,
     * or in this file's own documentation -- is not mistaken for one.
     */
    fun idIn(commentText: String): Long? =
        PATTERN.find(commentText.trim())?.groupValues?.get(1)?.toLongOrNull()
}
