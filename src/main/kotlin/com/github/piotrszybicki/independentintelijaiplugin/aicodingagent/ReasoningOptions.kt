package com.github.piotrszybicki.independentintelijaiplugin.aicodingagent

import com.github.piotrszybicki.independentintelijaiplugin.settings.AgentConfiguration
import com.github.piotrszybicki.independentintelijaiplugin.settings.Effort
import com.github.piotrszybicki.independentintelijaiplugin.settings.ThinkingMode
import com.google.gson.JsonObject

data class ReasoningOptions(
    val effort: Effort,
    val thinking: ThinkingMode,
) {

    fun thinkingJson(model: String = "", maxTokens: Int = DEFAULT_MAX_TOKENS): JsonObject? = when (thinking) {
        ThinkingMode.PROVIDER_DEFAULT -> null
        ThinkingMode.OFF -> JsonObject().apply { addProperty("type", "disabled") }
        ThinkingMode.ADAPTIVE -> if (isLegacyAnthropicThinkingModel(model)) {
            JsonObject().apply {
                addProperty("type", "enabled")
                addProperty("budget_tokens", legacyThinkingBudget(maxTokens))
            }
        } else {
            JsonObject().apply {
                addProperty("type", "adaptive")
                addProperty("display", "summarized")
            }
        }
    }

    fun outputConfigJson(model: String = ""): JsonObject? = effort.wireValue
        ?.takeUnless { isLegacyAnthropicThinkingModel(model) }
        ?.let { value -> JsonObject().apply { addProperty("effort", value) } }

    private fun legacyThinkingBudget(maxTokens: Int): Int =
        minOf(DEFAULT_LEGACY_THINKING_BUDGET, (maxTokens - 1).coerceAtLeast(MIN_LEGACY_THINKING_BUDGET))

    private fun isLegacyAnthropicThinkingModel(model: String): Boolean =
        model.lowercase().contains("haiku")

    fun reasoningJson(): JsonObject? = when (thinking) {
        ThinkingMode.PROVIDER_DEFAULT -> null
        ThinkingMode.OFF -> reasoningEffort("none")
        ThinkingMode.ADAPTIVE -> reasoningEffort(effort.openAiValue ?: DEFAULT_OPENAI_EFFORT)
    }

    private fun reasoningEffort(value: String): JsonObject =
        JsonObject().apply { addProperty("effort", value) }

    companion object {
        private const val DEFAULT_MAX_TOKENS = 8000
        private const val MIN_LEGACY_THINKING_BUDGET = 1024
        private const val DEFAULT_LEGACY_THINKING_BUDGET = 4096
        private const val DEFAULT_OPENAI_EFFORT = "medium"

        val PROVIDER_DEFAULT = ReasoningOptions(Effort.PROVIDER_DEFAULT, ThinkingMode.PROVIDER_DEFAULT)

        fun from(configuration: AgentConfiguration): ReasoningOptions =
            ReasoningOptions(configuration.effort, configuration.thinking)
    }
}