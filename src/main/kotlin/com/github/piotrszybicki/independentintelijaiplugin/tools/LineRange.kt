package com.github.piotrszybicki.independentintelijaiplugin.tools

class LineRange(val text: String) {

    val endsWithNewline: Boolean = text.endsWith("\n")

    val lines: List<String> = when {
        text.isEmpty() -> emptyList()
        endsWithNewline -> text.split("\n").dropLast(1)
        else -> text.split("\n")
    }

    val lineCount: Int get() = lines.size

    fun startOffset(line: Int): Int {
        val index = line - 1
        var offset = 0
        for (i in 0 until index) offset += lines[i].length + 1
        return offset.coerceAtMost(text.length)
    }
}
