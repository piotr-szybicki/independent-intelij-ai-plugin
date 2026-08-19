package com.github.piotrszybicki.independentintelijaiplugin.agents

import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool
import com.github.piotrszybicki.independentintelijaiplugin.skills.SkillFrontmatter
import java.io.File

data class AgentToolPolicy(
    val allowed: Set<String>?,
    val denied: Set<String>,
    val takesAll: Boolean = false,
) {

    val saysNothing: Boolean get() = allowed == null && denied.isEmpty() && !takesAll

    fun keeps(name: String): Boolean = (allowed == null || name in allowed) && name !in denied

    fun select(
        everything: List<AICodingAgentTool>,
        enabledInSettings: List<AICodingAgentTool>,
    ): List<AICodingAgentTool> =
        if (saysNothing) enabledInSettings else everything.filter { keeps(it.name) }

    fun describe(): String = when {
        saysNothing -> "whatever the main chat has, from the settings page"
        allowed != null && denied.isEmpty() -> allowed.sorted().joinToString(", ")
        allowed != null -> (allowed - denied).sorted().joinToString(", ")
        denied.isNotEmpty() -> "every tool except ${denied.sorted().joinToString(", ")}"
        else -> "every tool"
    }

    companion object {

        val INHERIT = AgentToolPolicy(null, emptySet())

        fun of(names: Collection<String>): AgentToolPolicy {
            val allowed = mutableSetOf<String>()
            val denied = mutableSetOf<String>()
            var takesAll = false
            for (entry in names.map { it.trim() }.filter { it.isNotEmpty() }) {
                when {
                    entry == "*" || entry.equals("all", ignoreCase = true) -> takesAll = true
                    entry.startsWith("-") || entry.startsWith("!") -> denied += entry.drop(1).trim()
                    else -> allowed += entry
                }
            }
            denied.remove("")
            allowed.remove("")
            return AgentToolPolicy(allowed.takeIf { it.isNotEmpty() }, denied, takesAll)
        }

        fun parse(value: String?): AgentToolPolicy = of(value.orEmpty().split(',', '\n'))
    }
}

data class AgentDefinition(
    val name: String,
    val description: String,
    val prompt: String,
    val tools: AgentToolPolicy = AgentToolPolicy.INHERIT,
    val configurationName: String? = null,
    val model: String? = null,
    val specTemplate: String = "",
    val file: File? = null,
    val origin: String = BUILT_IN,
) {

    val isBuiltIn: Boolean get() = origin == BUILT_IN

    val isFromFile: Boolean get() = origin == FILE

    companion object {

        const val BUILT_IN = "built-in"
        const val FILE = "file"
        const val CONFIGURATION = "settings file"

        private const val MAX_READ_CHARS = 256 * 1024

        private const val MAX_DESCRIPTION_CHARS = 512

        fun read(file: File): AgentDefinition? {
            val text = runCatching { head(file) }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
            val frontmatter = SkillFrontmatter.parse(text)
            val body = SkillFrontmatter.stripFrontmatter(text).trim()

            val name = frontmatter["name"]?.takeIf { it.isNotBlank() } ?: nameOf(file)
            val description = frontmatter["description"]?.takeIf { it.isNotBlank() }
                ?: firstParagraph(body)
                ?: return null
            if (body.isEmpty()) return null

            return AgentDefinition(
                name = name,
                description = truncate(description),
                prompt = body,
                tools = AgentToolPolicy.parse(frontmatter["tools"]),
                configurationName = frontmatter["configuration"]?.takeIf { it.isNotBlank() },
                model = frontmatter["model"]?.takeIf { it.isNotBlank() },
                specTemplate = frontmatter["spec_template"].orEmpty().trim(),
                file = file.absoluteFile,
                origin = FILE,
            )
        }

        private fun nameOf(file: File): String {
            val base = file.nameWithoutExtension
            if (!base.equals("AGENT", ignoreCase = true)) return base
            return file.parentFile?.name?.takeIf { it.isNotBlank() } ?: base
        }

        private fun head(file: File): String {
            if (file.length() <= MAX_READ_CHARS) return file.readText()
            val buffer = CharArray(MAX_READ_CHARS)
            val read = file.bufferedReader().use { it.read(buffer) }
            return if (read <= 0) "" else String(buffer, 0, read)
        }

        private fun firstParagraph(body: String): String? =
            body.split(Regex("\\r?\\n\\s*\\r?\\n"))
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                ?.replace(Regex("\\s+"), " ")

        private fun truncate(value: String): String {
            val collapsed = value.replace(Regex("\\s+"), " ").trim()
            if (collapsed.length <= MAX_DESCRIPTION_CHARS) return collapsed
            return collapsed.take(MAX_DESCRIPTION_CHARS).substringBeforeLast(' ') + "..."
        }
    }
}
