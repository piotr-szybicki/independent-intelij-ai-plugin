package com.github.piotrszybicki.independentintelijaiplugin.settings

import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class UsageDatabaseConfig(
    val url: String,

    val enabled: Boolean,
) {

    val isActive: Boolean get() = enabled && url.isNotBlank()

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty(URL, url)
        addProperty(ENABLED, enabled)
    }

    companion object {

        const val SECTION = "usage-database"

        private const val URL = "url"
        private const val ENABLED = "enabled"

        val OFF = UsageDatabaseConfig(url = "", enabled = true)

        fun parse(text: String): UsageDatabaseConfig {
            if (text.isBlank()) return OFF

            val root = try {
                JsonParser.parseString(text)
            } catch (e: Exception) {
                throw AgentConfigurationException("not valid JSON: ${e.message}")
            }
            if (root == null || root.isJsonNull || !root.isJsonObject) return OFF

            val section = root.asJsonObject.get(SECTION) ?: return OFF
            if (!section.isJsonObject) {
                throw AgentConfigurationException("\"$SECTION\" must be an object")
            }
            val entry = section.asJsonObject

            val url = entry.get(URL)?.let {
                if (it.isJsonPrimitive) it.asString.trim()
                else throw AgentConfigurationException("\"$SECTION\".$URL must be a URL in quotes")
            }.orEmpty()

            val enabled = entry.get(ENABLED)?.let {
                when (it.takeIf { element -> element.isJsonPrimitive }?.asString?.trim()?.lowercase()) {
                    "true", "yes", "on" -> true
                    "false", "no", "off" -> false
                    else -> throw AgentConfigurationException("\"$SECTION\".$ENABLED must be true or false")
                }
            } ?: true

            return UsageDatabaseConfig(url, enabled)
        }
    }
}
