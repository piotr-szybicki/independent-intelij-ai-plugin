package com.github.piotrszybicki.independentintelijaiplugin.cleanup

data class FoundComment(
    val fileText: String,
    val start: Int,
    val end: Int,
) {
    val text: String get() = fileText.substring(start, end)
}
