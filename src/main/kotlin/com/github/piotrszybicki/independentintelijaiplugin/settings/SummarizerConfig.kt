package com.github.piotrszybicki.independentintelijaiplugin.settings

import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class SummarizerConfig(
    val configurationName: String,

    val model: String,

    val maxTokens: Int,

    val minInputTokens: Int,

    val thinking: ThinkingMode,

    val prompt: String,
) {

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty(CONFIGURATION, configurationName)
        addProperty(MODEL, model)
        addProperty(MAX_TOKENS, maxTokens)
        addProperty(MIN_INPUT_TOKENS, minInputTokens)
        addProperty(THINKING, thinking.fileName)
        addProperty(PROMPT, prompt)
    }

    fun describe(): String {
        val where = configurationName.takeIf { it.isNotBlank() } ?: "the provider this chat is on"
        val what = model.takeIf { it.isNotBlank() } ?: "that provider's own model"
        return "$what via $where"
    }

    companion object {

        const val SECTION = "summarizer"

        private const val CONFIGURATION = "configuration"
        private const val MODEL = "model"
        private const val MAX_TOKENS = "max-tokens"
        private const val MIN_INPUT_TOKENS = "min-input-tokens"
        private const val THINKING = "thinking"
        private const val PROMPT = "prompt"

        const val DEFAULT_MAX_TOKENS = 1500

        const val DEFAULT_MIN_INPUT_TOKENS = 400

        val DEFAULT_THINKING = ThinkingMode.OFF

        val DEFAULT = SummarizerConfig(
            configurationName = "",
            model = "",
            maxTokens = DEFAULT_MAX_TOKENS,
            minInputTokens = DEFAULT_MIN_INPUT_TOKENS,
            thinking = DEFAULT_THINKING,
            prompt = "",
        )

        fun parse(text: String): SummarizerConfig {
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
            val entry = section.asJsonObject

            return SummarizerConfig(
                configurationName = entry.string(CONFIGURATION),
                model = entry.string(MODEL),
                maxTokens = entry.int(MAX_TOKENS, DEFAULT_MAX_TOKENS, minimum = 1),
                minInputTokens = entry.int(MIN_INPUT_TOKENS, DEFAULT_MIN_INPUT_TOKENS, minimum = 0),
                thinking = entry.thinking(),
                prompt = entry.string(PROMPT),
            )
        }

        private fun JsonObject.string(field: String): String {
            val element = get(field) ?: return ""
            if (!element.isJsonPrimitive) {
                throw AgentConfigurationException("\"$SECTION\".$field must be text in quotes")
            }
            return element.asString.trim()
        }

        private fun JsonObject.thinking(): ThinkingMode {
            val written = string(THINKING)
            if (written.isBlank()) return DEFAULT_THINKING
            return ThinkingMode.parse(written)
                ?: throw AgentConfigurationException(
                    "\"$SECTION\".$THINKING is \"$written\" -- expected one of " +
                        ThinkingMode.entries.joinToString(", ") { it.fileName },
                )
        }

        private fun JsonObject.int(field: String, fallback: Int, minimum: Int): Int {
            val element = get(field) ?: return fallback
            val value = element.takeIf { it.isJsonPrimitive }?.asString?.trim()?.toIntOrNull()
                ?: throw AgentConfigurationException("\"$SECTION\".$field must be a whole number")
            if (value < minimum) {
                throw AgentConfigurationException("\"$SECTION\".$field must be at least $minimum")
            }
            return value
        }
    }
}
