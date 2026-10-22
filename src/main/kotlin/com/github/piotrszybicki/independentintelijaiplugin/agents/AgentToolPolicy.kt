package com.github.piotrszybicki.independentintelijaiplugin.agents

import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool
import com.github.piotrszybicki.independentintelijaiplugin.tools.ToolNames

data class AgentToolPolicy(
    val allowed: Set<String>?,
    val denied: Set<String>,
    val takesAll: Boolean = false,
    val additions: Set<String> = emptySet(),
) {
    val saysNothing: Boolean get() = allowed == null && denied.isEmpty() && !takesAll && additions.isEmpty()

    fun keeps(name: String): Boolean =
        name !in denied &&
            when {
                takesAll -> true
                allowed != null -> name in allowed || name in additions
                additions.isNotEmpty() -> name in additions
                else -> true
            }

    fun select(
        everything: List<AICodingAgentTool>,
        enabledInSettings: List<AICodingAgentTool>,
    ): List<AICodingAgentTool> {
        val base =
            when {
                saysNothing -> enabledInSettings
                allowed != null || takesAll || denied.isNotEmpty() -> everything.filter { keeps(it.name) }
                else -> enabledInSettings
            }
        return (base + everything.filter { it.name in additions })
            .distinctBy { it.name }
            .filter { it.name !in denied }
    }

    fun describe(): String =
        when {
            saysNothing -> "whatever the main chat has, from the settings page"
            allowed == null && !takesAll && denied.isEmpty() ->
                "whatever the main chat has, plus ${additions.sorted().joinToString(", ")}"
            allowed != null && denied.isEmpty() -> (allowed + additions).sorted().joinToString(", ")
            allowed != null -> ((allowed + additions) - denied).sorted().joinToString(", ")
            denied.isNotEmpty() -> "every tool except ${denied.sorted().joinToString(", ")}"
            else -> "every tool"
        }

    companion object {
        val INHERIT = AgentToolPolicy(null, emptySet())

        fun of(names: Collection<String>): AgentToolPolicy {
            val allowed = mutableSetOf<String>()
            val denied = mutableSetOf<String>()
            val additions = mutableSetOf<String>()
            var takesAll = false
            for (entry in names.map { it.trim() }.filter { it.isNotEmpty() }) {
                when {
                    entry == "*" || entry.equals("all", ignoreCase = true) -> takesAll = true
                    entry.startsWith("-") || entry.startsWith("!") -> denied += ToolNames.current(entry.drop(1).trim())
                    entry.startsWith("+") -> additions += ToolNames.current(entry.drop(1).trim())
                    else -> allowed += ToolNames.current(entry)
                }
            }
            denied.remove("")
            allowed.remove("")
            additions.remove("")
            return AgentToolPolicy(allowed.takeIf { it.isNotEmpty() }, denied, takesAll, additions)
        }

        fun parse(value: String?): AgentToolPolicy = of(value.orEmpty().split(',', '\n'))
    }
}
