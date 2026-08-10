package com.github.piotrszybicki.independentintelijaiplugin.skills

import java.io.File

/**
 * One configured directory to look for skills in.
 *
 * [directory] is null when the line could not be turned into a path at all, in which case [error]
 * says why. A root that simply does not exist is not an error here -- the defaults name locations
 * most projects will not have, and a listing that complained about every one of them would be noise.
 */
data class SkillRoot(
    /** Exactly what the user typed, so a message about this root names the line they wrote. */
    val configured: String,
    val directory: File?,
    val error: String? = null,
) {

    companion object {

        /** Same convention as the MCP server field, so a path can name a variable instead of a home directory. */
        private val ENV_REFERENCE = Regex("""\$\{env:([A-Za-z_][A-Za-z0-9_]*)}""")

        /**
         * What a fresh install looks for: the standard project location, a short one for projects
         * that prefer it, and the per-user directory shared across every project.
         *
         * The last is outside any workspace on purpose -- skills a developer carries between
         * projects are the ones worth having by default.
         */
        val DEFAULT_PATHS = """
            .claude/skills
            .skills
            ~/.claude/skills
        """.trimIndent()

        /**
         * Reads the configured paths, one per line.
         *
         * Relative paths are resolved against [projectBase] and absolute ones are taken as they are,
         * which is what lets a root sit anywhere on disk. Duplicates are dropped by canonical path
         * rather than by text, so naming the same directory twice -- directly and through a symlink,
         * or as `.skills` and as an absolute path -- lists its skills once.
         */
        fun parseAll(text: String, projectBase: File?): List<SkillRoot> {
            val seen = mutableSetOf<String>()
            return text.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { line -> parseOne(line, projectBase) }
                .filter { root ->
                    val key = root.directory?.path ?: root.configured
                    seen.add(key)
                }
        }

        private fun parseOne(line: String, projectBase: File?): SkillRoot {
            val expanded = try {
                expand(line)
            } catch (e: IllegalArgumentException) {
                return SkillRoot(line, null, e.message)
            }

            val file = File(expanded)
            val resolved = when {
                file.isAbsolute -> file
                projectBase != null -> File(projectBase, expanded)
                // A relative path with nothing to resolve it against would land wherever the IDE
                // happens to have been started from, which is never what was meant.
                else -> return SkillRoot(line, null, "relative path, but the project has no directory on disk")
            }

            val canonical = runCatching { resolved.canonicalFile }.getOrDefault(resolved.absoluteFile)
            return SkillRoot(line, canonical)
        }

        private fun expand(path: String): String {
            val withEnv = ENV_REFERENCE.replace(path) { match ->
                val variable = match.groupValues[1]
                System.getenv(variable)
                    ?: throw IllegalArgumentException("the environment variable $variable is not set in the IDE's environment")
            }
            // Only a leading `~`: anywhere else it is a legal character in a file name, and on
            // Windows it is a real one -- short 8.3 names are full of them.
            return when {
                withEnv == "~" -> System.getProperty("user.home")
                withEnv.startsWith("~/") || withEnv.startsWith("~\\") ->
                    System.getProperty("user.home") + withEnv.substring(1)
                else -> withEnv
            }
        }
    }
}
