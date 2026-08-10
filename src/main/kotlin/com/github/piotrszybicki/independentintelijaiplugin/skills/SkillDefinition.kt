package com.github.piotrszybicki.independentintelijaiplugin.skills

import java.io.File

/**
 * One skill: a directory holding a `SKILL.md` whose frontmatter says what it is for.
 *
 * Only [name] and [description] travel to the model on every turn -- the body stays on disk until
 * something decides to read it. That split is the whole point of the format: a skill's instructions
 * and its bundled reference files can be as long as they like, because a listing entry costs a line
 * and the rest costs nothing until it is needed.
 */
data class SkillDefinition(
    val name: String,
    val description: String,
    /** The `SKILL.md` itself, absolute -- a root may sit outside the project, so nothing here is relative. */
    val file: File,
) {

    companion object {

        /**
         * Cap on the listing text for one skill, following the format's own limit on `description`
         * plus `when_to_use`. Descriptions are written to be matched against, not read, and a
         * verbose one would otherwise charge every turn of every conversation for itself.
         */
        private const val MAX_DESCRIPTION_CHARS = 1_536

        /**
         * Frontmatter lives at the top of the file, and the fallback description only needs the
         * first paragraph after it, so there is never a reason to pull a long reference document
         * into memory to find out what it is called.
         */
        private const val MAX_READ_CHARS = 64 * 1024

        /**
         * Reads [file] into a definition, or returns null when it holds nothing usable.
         *
         * Deliberately forgiving: `name` falls back to the directory name and `description` to the
         * first paragraph of the body, both of which the format allows, and neither field being
         * present is the only thing that disqualifies a file. A skill that will not parse should
         * cost the user that one skill, not the whole listing.
         */
        fun read(file: File): SkillDefinition? {
            val text = runCatching { head(file) }.getOrNull() ?: return null
            val frontmatter = SkillFrontmatter.parse(text)

            // The directory is what the user types and what the format treats as the real name; a
            // `name` field is a display label over the top of it.
            val fallbackName = file.parentFile?.name?.takeIf { it.isNotBlank() }
                ?: file.nameWithoutExtension
            val name = frontmatter["name"]?.takeIf { it.isNotBlank() } ?: fallbackName

            val described = frontmatter["description"]?.takeIf { it.isNotBlank() }
                ?: firstParagraph(text)
                ?: return null

            // `when_to_use` carries the trigger phrases that make a skill findable, so it belongs in
            // the listing next to the description rather than in the body nobody has read yet.
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

        /** The body's opening paragraph, used when the frontmatter never says what the skill is for. */
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

/**
 * Reads the `key: value` pairs out of a `SKILL.md`'s YAML frontmatter.
 *
 * Hand-written rather than pulled from a YAML library, because the plugin has no YAML dependency and
 * this needs two string fields out of a block that is a flat map by convention. It understands plain
 * and quoted scalars and the `|` and `>` block forms that long descriptions are written in, and it
 * ignores everything nested -- a key must start at column zero, so the inside of a `metadata:` map or
 * an `allowed-tools:` list can never be mistaken for a top-level field.
 */
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
            // Blank, commented, or indented: nothing at the top level of the map, so nothing wanted.
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
                // A block scalar runs until the indentation stops, which is also where the next
                // top-level key begins -- so this consumes exactly the continuation lines.
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

    /** The markdown below the frontmatter, or the whole text when there is none. */
    fun stripFrontmatter(text: String): String {
        val lines = text.lines().map { it.trimEnd('\r') }
        val open = openingFence(lines) ?: return text
        val close = closingFence(lines, open + 1) ?: return text
        return lines.drop(close + 1).joinToString("\n")
    }

    /** The frontmatter has to be the first thing in the file; a `---` further down is a horizontal rule. */
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
        // Unterminated: the file opens with something that looked like a fence but never closed, so
        // treating the rest of it as fields would invent metadata out of prose.
        return null
    }

    private fun unquote(value: String): String {
        if (value.length >= 2) {
            val first = value.first()
            if ((first == '"' || first == '\'') && value.last() == first) {
                return value.substring(1, value.length - 1)
            }
        }
        // An unquoted scalar ends at a comment. YAML wants whitespace before the `#`, which also
        // keeps a `#` inside a description from truncating it.
        return value.substringBefore(" #").trim()
    }
}
