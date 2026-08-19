package com.github.piotrszybicki.independentintelijaiplugin.settings

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class FindInFilesConfig(
    val blockedPhrases: List<String>,
) {

    fun blocking(query: String): String? {
        val normalised = query.trim()
        if (normalised.isEmpty()) return null
        return blockedPhrases.firstOrNull { it.equals(normalised, ignoreCase = true) }
    }

    fun toJson(): JsonObject = JsonObject().apply {
        add(BLOCKED_PHRASES, JsonArray().apply { blockedPhrases.forEach { add(it) } })
    }

    companion object {

        const val SECTION = "find-in-files"

        private const val BLOCKED_PHRASES = "blocked-phrases"

        val DEFAULT = FindInFilesConfig(blockedPhrases = emptyList())

        fun parse(text: String): FindInFilesConfig {
            if (text.isBlank()) return DEFAULT

            val root = try {
                JsonParser.parseString(text)
            } catch (e: Exception) {
                throw AgentConfigurationException("not valid JSON: ${e.message}")
            }
            if (root == null || root.isJsonNull || !root.isJsonObject) return DEFAULT

            val section = root.asJsonObject.get(SECTION) ?: return DEFAULT
            if (!section.isJsonObject) {
                throw AgentConfigurationException("\"$SECTION\" must be an object")
            }

            val element = section.asJsonObject.get(BLOCKED_PHRASES) ?: return DEFAULT
            if (!element.isJsonArray) {
                throw AgentConfigurationException(
                    "\"$SECTION\".$BLOCKED_PHRASES must be an array of phrases in quotes",
                )
            }
            val phrases = element.asJsonArray.map {
                if (!it.isJsonPrimitive) {
                    throw AgentConfigurationException("\"$SECTION\".$BLOCKED_PHRASES must hold phrases in quotes")
                }
                it.asString.trim()
            }
            return FindInFilesConfig(phrases.filter { it.isNotBlank() }.distinct())
        }
    }
}
