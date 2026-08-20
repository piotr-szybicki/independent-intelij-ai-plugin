package com.github.piotrszybicki.independentintelijaiplugin.settings

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * What a new chat starts with, written in the project's own settings file rather than in the IDE.
 *
 * `tools` and `mcp-tools` are null when the section leaves them out, and that is not the same as
 * an empty array: left out means *the settings page decides*, `[]` means this project deliberately
 * starts its chats with none of them. `skills` has no such distinction -- chats start with no
 * skills either way, so an empty list and a missing key both mean none.
 *
 * This is the default layer only. An agent's own tool list still narrows or replaces it in the
 * chats that agent starts, and the Tools button still overrides it for one chat.
 */
data class ConversationDefaultsConfig(
    val tools: List<String>?,
    val mcpTools: List<String>?,
    val skills: List<String>,
) {

    val saysNothing: Boolean get() = tools == null && mcpTools == null && skills.isEmpty()

    /* A null array is left out rather than written as [], because the two do not mean the same. */
    fun toJson(): JsonObject = JsonObject().apply {
        tools?.let { names -> add(TOOLS, JsonArray().apply { names.forEach { add(it) } }) }
        mcpTools?.let { names -> add(MCP_TOOLS, JsonArray().apply { names.forEach { add(it) } }) }
        add(SKILLS, JsonArray().apply { skills.forEach { add(it) } })
    }

    companion object {

        const val SECTION = "conversation-defaults"

        private const val TOOLS = "tools"
        private const val MCP_TOOLS = "mcp-tools"
        private const val SKILLS = "skills"

        /** No section at all: every chat starts on the settings page, with no skills. */
        val DEFAULT = ConversationDefaultsConfig(null, null, emptyList())

        fun parse(text: String): ConversationDefaultsConfig {
            if (text.isBlank()) return DEFAULT

            val root = try {
                JsonParser.parseString(text)
            } catch (e: Exception) {
                throw AgentConfigurationException("not valid JSON: ${e.message}")
            }
            if (root == null || root.isJsonNull || !root.isJsonObject) return DEFAULT

            val element = root.asJsonObject.get(SECTION) ?: return DEFAULT
            if (!element.isJsonObject) {
                throw AgentConfigurationException("\"$SECTION\" must be an object")
            }
            val section = element.asJsonObject

            return ConversationDefaultsConfig(
                tools = section.names(TOOLS),
                mcpTools = section.names(MCP_TOOLS),
                skills = section.names(SKILLS).orEmpty(),
            )
        }

        private fun JsonObject.names(field: String): List<String>? {
            val element = get(field) ?: return null
            if (!element.isJsonArray) {
                throw AgentConfigurationException("\"$SECTION\".$field must be an array of names in quotes")
            }
            return element.asJsonArray.map {
                if (!it.isJsonPrimitive) {
                    throw AgentConfigurationException("\"$SECTION\".$field must hold names in quotes")
                }
                it.asString.trim()
            }.filter { it.isNotBlank() }.distinct()
        }
    }
}
