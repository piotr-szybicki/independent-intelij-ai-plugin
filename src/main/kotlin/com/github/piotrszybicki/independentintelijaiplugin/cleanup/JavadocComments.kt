package com.github.piotrszybicki.independentintelijaiplugin.cleanup

object JavadocComments {

    data class Removal(val start: Int, val end: Int)

    fun isJavadoc(text: String): Boolean =
        text.length >= 5 && text.startsWith("/**") && text.endsWith("*/")

    fun isBlank(text: String): Boolean {
        if (!isJavadoc(text)) return false
        val inner = text.substring(3, text.length - 2)
        return inner.lineSequence().all { it.trim().trimStart('*').isBlank() }
    }

    fun isAlone(fileText: String, commentStart: Int, commentEnd: Int): Boolean {
        val lineStart = fileText.lastIndexOf('\n', commentStart - 1) + 1
        val lineEnd = fileText.indexOf('\n', commentEnd).takeIf { it >= 0 } ?: fileText.length
        return fileText.substring(lineStart, commentStart).isBlank() &&
            fileText.substring(commentEnd, lineEnd).isBlank()
    }

    fun removalFor(fileText: String, commentStart: Int, commentEnd: Int): Removal {
        val lineStart = fileText.lastIndexOf('\n', commentStart - 1) + 1
        val lineEnd = fileText.indexOf('\n', commentEnd).takeIf { it >= 0 } ?: fileText.length

        if (isAlone(fileText, commentStart, commentEnd)) {
            // The line break goes too, unless the comment ends the file and there is none.
            return Removal(lineStart, if (lineEnd < fileText.length) lineEnd + 1 else fileText.length)
        }

        var end = commentEnd
        while (end < fileText.length && (fileText[end] == ' ' || fileText[end] == '\t')) end++
        return Removal(commentStart, end)
    }
}
