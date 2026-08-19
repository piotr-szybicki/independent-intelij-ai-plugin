package com.github.piotrszybicki.independentintelijaiplugin.skills

import java.io.File

data class SkillDefinition(
    val name: String,
    val description: String,
    val file: File,
) {

    companion object {

        private const val MAX_DESCRIPTION_CHARS = 1_536

        private const val MAX_READ_CHARS = 64 * 1024

        fun read(file: File): SkillDefinition? {
            val text = runCatching { head(file) }.getOrNull() ?: return null
            val frontmatter = SkillFrontmatter.parse(text)

            val fallbackName = file.parentFile?.name?.takeIf { it.isNotBlank() }
                ?: file.nameWithoutExtension
            val name = frontmatter["name"]?.takeIf { it.isNotBlank() } ?: fallbackName

            val described = frontmatter["description"]?.takeIf { it.isNotBlank() }
                ?: firstParagraph(text)
                ?: return null

            val whenToUse = frontmatter["when_to_use"]?.takeIf { it.isNotBlank() }
            val full = if (whenToUse == null) described else "$described $whenToUse"

            return SkillDefinition(name, truncate(full), file.absoluteFile)
        }

        private fun head(file: File): String {
            if (file.length() <= MAX_READ_CHARS) return file.readText()
            val buffer = CharArray(MAX_READ_CHARS)
            val read = file.bufferedReader().use { it.read(buffer) }
            return if (read <= 0) "" else String(buffer, 0, read)
        }

        private fun firstParagraph(text: String): String? {
            val body = SkillFrontmatter.stripFrontmatter(text)
            return body.split(Regex("\\r?\\n\\s*\\r?\\n"))
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                ?.replace(Regex("\\s+"), " ")
        }

        private fun truncate(value: String): String {
            val collapsed = value.replace(Regex("\\s+"), " ").trim()
            if (collapsed.length <= MAX_DESCRIPTION_CHARS) return collapsed
            return collapsed.take(MAX_DESCRIPTION_CHARS).substringBeforeLast(' ') + "..."
        }
    }
}

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
                fields[key] = if (folded) {
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

    private fun closingFence(lines: List<String>, from: Int): Int? {
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
