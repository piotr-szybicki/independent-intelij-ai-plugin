package com.github.piotrszybicki.independentintelijaiplugin.skills

import java.io.File

data class SkillRoot(
    val configured: String,
    val directory: File?,
    val error: String? = null,
) {

    companion object {

        private val ENV_REFERENCE = Regex("""\$\{env:([A-Za-z_][A-Za-z0-9_]*)}""")

        val DEFAULT_PATHS = """
            .claude/skills
            .skills
            ~/.claude/skills
        """.trimIndent()

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
            return when {
                withEnv == "~" -> System.getProperty("user.home")
                withEnv.startsWith("~/") || withEnv.startsWith("~\\") ->
                    System.getProperty("user.home") + withEnv.substring(1)
                else -> withEnv
            }
        }
    }
}
