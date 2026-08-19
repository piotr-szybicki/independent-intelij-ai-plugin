package com.github.piotrszybicki.independentintelijaiplugin.changes

data class Hunk(
    val oldStart: Int,
    val oldEnd: Int,
    val newStart: Int,
    val newEnd: Int,
)
