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

            val fallbackName =
                file.parentFile?.name?.takeIf { it.isNotBlank() }
                    ?: file.nameWithoutExtension
            val name = frontmatter["name"]?.takeIf { it.isNotBlank() } ?: fallbackName

            val described =
                frontmatter["description"]?.takeIf { it.isNotBlank() }
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
            return body
                .split(Regex("\\r?\\n\\s*\\r?\\n"))
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
