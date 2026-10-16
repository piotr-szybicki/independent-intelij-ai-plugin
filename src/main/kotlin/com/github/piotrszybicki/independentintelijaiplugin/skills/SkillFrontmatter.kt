package com.github.piotrszybicki.independentintelijaiplugin.skills

object SkillFrontmatter {
    private const val FENCE = "---"

    fun parse(text: String): Map<String, String> {
        val lines = text.lines().map { it.trimEnd('\r') }
        val open = openingFence(lines) ?: return emptyMap()
        val close = closingFence(lines, open + 1) ?: return emptyMap()

        val fields = mutableMapOf<String, String>()
        var i = open + 1
        while (i < close) {
            val line = lines[i]
            if (line.isBlank() || line.startsWith(" ") || line.startsWith("\t") || line.trimStart().startsWith("#")) {
                i++
                continue
            }

            val colon = line.indexOf(':')
            if (colon <= 0) {
                i++
                continue
            }

            val key = line.substring(0, colon).trim()
            val rest = line.substring(colon + 1).trim()

            if (rest.startsWith("|") || rest.startsWith(">")) {
                val folded = rest.startsWith(">")
                val body = mutableListOf<String>()
                i++
                while (i < close && (lines[i].isBlank() || lines[i].startsWith(" ") || lines[i].startsWith("\t"))) {
                    body.add(lines[i].trim())
                    i++
                }
                fields[key] =
                    if (folded) {
                        body.filter { it.isNotEmpty() }.joinToString(" ")
                    } else {
                        body.joinToString("\n").trim()
                    }
            } else {
                fields[key] = unquote(rest)
                i++
            }
        }
        return fields
    }

    fun stripFrontmatter(text: String): String {
        val lines = text.lines().map { it.trimEnd('\r') }
        val open = openingFence(lines) ?: return text
        val close = closingFence(lines, open + 1) ?: return text
        return lines.drop(close + 1).joinToString("\n")
    }

    private fun openingFence(lines: List<String>): Int? {
        val first = lines.indexOfFirst { it.isNotBlank() }
        if (first < 0 || lines[first].trim() != FENCE) return null
        return first
    }

    private fun closingFence(
        lines: List<String>,
        from: Int,
    ): Int? {
        for (i in from until lines.size) {
            val trimmed = lines[i].trim()
            if (trimmed == FENCE || trimmed == "...") return i
        }
        return null
    }

    private fun unquote(value: String): String {
        if (value.length >= 2) {
            val first = value.first()
            if ((first == '"' || first == '\'') && value.last() == first) {
                return value.substring(1, value.length - 1)
            }
        }
        return value.substringBefore(" #").trim()
    }
}
