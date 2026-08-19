package com.github.piotrszybicki.independentintelijaiplugin.cleanup

object CommentMarker {

    private val PATTERN = Regex("""^//\s*comment_id:\s*(\d+)\s*$""")

    fun of(id: Long): String = "// comment_id: $id"

    fun idIn(commentText: String): Long? =
        PATTERN.find(commentText.trim())?.groupValues?.get(1)?.toLongOrNull()
}
