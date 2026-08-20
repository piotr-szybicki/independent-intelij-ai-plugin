package com.github.piotrszybicki.independentintelijaiplugin.settings

/*
 * One chat's own answer to "what may be called here".
 *
 * Tools and MCP tools inherit until the chat is given a set of its own: `overridden` is what
 * separates "this chat has not been told anything" from "this chat was told to take nothing", and
 * while it is false the two tool lists are ignored.
 *
 * Skills do not inherit. A new chat starts with none, whatever the settings page lists, and a skill
 * becomes available only by being ticked in the dialog or picked from the / popup in the chat
 * window. The settings page decides which skills exist to be picked; this decides which of them
 * this chat can see.
 *
 * All of it is saved with the chat, so re-opening one brings its selection back with it.
 */
data class ConversationTools(
    val overridden: Boolean = false,
    val tools: List<String> = emptyList(),
    val mcpTools: List<String> = emptyList(),
    val skills: List<String> = emptyList(),
) {

    val toolNames: Set<String> get() = tools.toSet()

    val mcpToolNames: Set<String> get() = mcpTools.toSet()

    /** The skills this chat may use. Empty means none are described to the model at all. */
    val activeSkills: Set<String> get() = skills.toSet()

    val toolCount: Int get() = tools.size + mcpTools.size

    /** Whether anything has been chosen here, and so whether it is worth saving with the chat. */
    val isCustom: Boolean get() = overridden || skills.isNotEmpty()

    fun withSkill(name: String): ConversationTools =
        if (name in skills) this else copy(skills = skills + name)

    fun withoutSkill(name: String): ConversationTools =
        if (name in skills) copy(skills = skills - name) else this

    fun buttonText(): String {
        val toolPart = if (overridden) "Tools: $toolCount" else "Tools: inherited"
        if (skills.isEmpty()) return toolPart
        return "$toolPart, ${skills.size} skill" + if (skills.size == 1) "" else "s"
    }

    fun describe(): String {
        val toolPart = if (overridden) {
            "This chat uses its own set: ${tools.size} built-in tool(s) and ${mcpTools.size} MCP tool(s)."
        } else {
            "This chat uses whatever the settings page enables, narrowed by the agent's own tool list."
        }
        val skillPart = if (skills.isEmpty()) {
            "No skills are switched on for it."
        } else {
            "Skills switched on: ${skills.joinToString(", ")}."
        }
        return "$toolPart $skillPart"
    }

    companion object {
        val INHERIT = ConversationTools()
    }
}
