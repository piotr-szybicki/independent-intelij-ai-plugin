package com.github.piotrszybicki.independentintelijaiplugin.tools

object CappedText {
    fun format(
        text: String,
        maxChars: Int,
        narrowWith: String,
    ): String {
        if (text.length <= maxChars) return text
        return text.take(maxChars).substringBeforeLast('\n') +
            "\n\n[TRUNCATED at $maxChars characters -- $narrowWith to see the rest]"
    }
}
