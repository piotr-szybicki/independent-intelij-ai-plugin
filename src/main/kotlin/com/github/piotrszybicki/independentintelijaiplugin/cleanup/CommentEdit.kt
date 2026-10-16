package com.github.piotrszybicki.independentintelijaiplugin.cleanup

data class CommentEdit(
    val start: Int,
    val end: Int,
    val replacement: String,
)
