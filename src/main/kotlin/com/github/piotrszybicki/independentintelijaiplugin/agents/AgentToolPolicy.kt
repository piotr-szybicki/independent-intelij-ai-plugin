package com.github.piotrszybicki.independentintelijaiplugin.agents

import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool

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
    ): List<AICodingAgentTool> = if (saysNothing) enabledInSettings else everything.filter { keeps(it.name) }

    fun describe(): String =
        when {
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
