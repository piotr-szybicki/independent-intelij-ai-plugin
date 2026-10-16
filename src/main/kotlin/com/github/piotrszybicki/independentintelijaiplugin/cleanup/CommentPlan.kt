package com.github.piotrszybicki.independentintelijaiplugin.cleanup

interface CommentPlan {
    val commandName: String

    fun wants(commentText: String): Boolean

    fun edit(found: FoundComment): CommentEdit?
}
