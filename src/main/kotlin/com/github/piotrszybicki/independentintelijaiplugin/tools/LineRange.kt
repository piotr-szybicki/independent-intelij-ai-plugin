package com.github.piotrszybicki.independentintelijaiplugin.tools

/**
 * Line arithmetic shared by `read_project_file` and `edit_file_lines`, so the numbers the model
 * reads and the numbers it writes back mean the same thing.
 *
 * Lines are 1-based and inclusive, matching the editor gutter. A file ending in a newline is *not*
 * treated as having a trailing empty line -- "a\nb\n" is two lines, not three -- because the model
 * would otherwise be able to target a line that nothing is displayed on.
 */
class LineRange(val text: String) {

    val endsWithNewline: Boolean = text.endsWith("\n")

    val lines: List<String> = when {
        text.isEmpty() -> emptyList()
        endsWithNewline -> text.split("\n").dropLast(1)
        else -> text.split("\n")
    }

    val lineCount: Int get() = lines.size

    /**
     * Offset of the first character of 1-based line [line]. Accepts [lineCount] + 1, which resolves
     * to the end of the file -- that is what makes "insert before line N" able to append.
     */
    fun startOffset(line: Int): Int {
        val index = line - 1
        var offset = 0
        for (i in 0 until index) offset += lines[i].length + 1
        return offset.coerceAtMost(text.length)
    }
}
