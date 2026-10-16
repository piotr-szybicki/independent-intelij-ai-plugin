package com.github.piotrszybicki.independentintelijaiplugin.settings

import com.google.gson.JsonArray
import com.google.gson.JsonObject

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
    // What a spec file for this agent starts from when there is no reply to draft one out of.
    val specTemplate: String = "",
) {
    fun toJson(): JsonObject =
        JsonObject().apply {
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
