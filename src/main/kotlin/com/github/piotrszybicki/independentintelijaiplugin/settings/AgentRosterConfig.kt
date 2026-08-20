package com.github.piotrszybicki.independentintelijaiplugin.settings

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class AgentRosterEntry(
    val name: String,
    val description: String,
    val prompt: String,
    val tools: List<String>,
    val configurationName: String,
    val model: String,

    /*
     * The skills this agent starts its chats with switched on. Chats begin with none otherwise,
     * so this is how an agent that is meant to follow a written procedure gets it without the
     * user typing /name first.
     */
    val skills: List<String> = emptyList(),

    /* What a spec file for this agent starts from when there is no reply to draft one out of. */
    val specTemplate: String = "",
) {

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty(NAME, name)
        addProperty(DESCRIPTION, description)
        addProperty(PROMPT, prompt)
        add(TOOLS, JsonArray().apply { tools.forEach { add(it) } })
        add(SKILLS, JsonArray().apply { skills.forEach { add(it) } })
        addProperty(SPEC_TEMPLATE, specTemplate)
        addProperty(CONFIGURATION, configurationName)
        addProperty(MODEL, model)
    }

    companion object {
        const val NAME = "name"
        const val DESCRIPTION = "description"
        const val PROMPT = "prompt"
        const val TOOLS = "tools"
        const val SKILLS = "skills"
        const val SPEC_TEMPLATE = "spec-template"
        const val CONFIGURATION = "configuration"
        const val MODEL = "model"
    }
}

data class AgentRosterConfig(val agents: List<AgentRosterEntry>) {

    fun forAgent(name: String): AgentRosterEntry? = agents.firstOrNull { it.name == name }

    fun toJson(): JsonArray = JsonArray().apply { agents.forEach { add(it.toJson()) } }

    companion object {

        const val SECTION = "agents"

        val EMPTY = AgentRosterConfig(emptyList())

        fun parse(text: String): AgentRosterConfig {
            if (text.isBlank()) return EMPTY

            val root = try {
                JsonParser.parseString(text)
            } catch (e: Exception) {
                throw AgentConfigurationException("not valid JSON: ${e.message}")
            }
            if (root == null || root.isJsonNull || !root.isJsonObject) return EMPTY

            val section = root.asJsonObject.get(SECTION) ?: return EMPTY
            if (!section.isJsonArray) {
                throw AgentConfigurationException("\"$SECTION\" must be an array of agents")
            }

            val seen = mutableSetOf<String>()
            val entries = section.asJsonArray.mapIndexed { index, element ->
                if (!element.isJsonObject) {
                    throw AgentConfigurationException("\"$SECTION\" entry ${index + 1} must be an object")
                }
                parseOne(index, element.asJsonObject).also {
                    if (!seen.add(it.name)) {
                        throw AgentConfigurationException("\"$SECTION\" names \"${it.name}\" twice")
                    }
                }
            }
            return AgentRosterConfig(entries)
        }

        private fun parseOne(index: Int, entry: JsonObject): AgentRosterEntry {
            val name = entry.string(AgentRosterEntry.NAME).orEmpty().trim()
            if (name.isBlank()) {
                throw AgentConfigurationException("\"$SECTION\" entry ${index + 1} has no \"${AgentRosterEntry.NAME}\"")
            }

            return AgentRosterEntry(
                name = name,
                description = entry.string(AgentRosterEntry.DESCRIPTION).orEmpty().trim(),
                prompt = entry.string(AgentRosterEntry.PROMPT).orEmpty().trim(),
                tools = entry.names(name, AgentRosterEntry.TOOLS),
                skills = entry.names(name, AgentRosterEntry.SKILLS),
                specTemplate = entry.string(AgentRosterEntry.SPEC_TEMPLATE).orEmpty().trim(),
                configurationName = entry.string(AgentRosterEntry.CONFIGURATION).orEmpty().trim(),
                model = entry.string(AgentRosterEntry.MODEL).orEmpty().trim(),
            )
        }

        private fun JsonObject.string(field: String): String? =
            get(field)?.takeIf { it.isJsonPrimitive }?.asString

        private fun JsonObject.names(agent: String, field: String): List<String> {
            val element = get(field) ?: return emptyList()
            if (!element.isJsonArray) {
                throw AgentConfigurationException(
                    "\"$SECTION\".\"$agent\".$field must be an array of names",
                )
            }
            return element.asJsonArray.map {
                if (!it.isJsonPrimitive) {
                    throw AgentConfigurationException(
                        "\"$SECTION\".\"$agent\".$field must hold names in quotes",
                    )
                }
                it.asString.trim()
            }.filter { it.isNotBlank() }.distinct()
        }
    }
}
