package com.github.piotrszybicki.independentintelijaiplugin.aicodingagent

import com.github.piotrszybicki.independentintelijaiplugin.settings.AICodingAgentSettingsState
import com.github.piotrszybicki.independentintelijaiplugin.settings.Effort
import com.github.piotrszybicki.independentintelijaiplugin.settings.ThinkingMode
import com.google.gson.JsonObject

/**
 * How much the model is asked to think, carried down to [AICodingAgentClient] with the rest of the
 * request.
 *
 * Its own type rather than two more parameters on the agent loop, and separate from
 * [AICodingAgentEndpoint] because none of it is about the transport: these change what the model
 * does, not where the request goes.
 *
 * Both fields render to nothing when left at their provider default, which is what makes this safe
 * to send from a plugin pointed at an arbitrary endpoint -- the older models reject `effort`, and a
 * gateway may reject either.
 */
data class ReasoningOptions(
    val effort: Effort,
    val thinking: ThinkingMode,
) {

    /**
     * The request's `thinking` field, or null to leave it out.
     *
     * `display` is asked for whenever thinking is on, because the current models default to
     * returning the blocks with their text emptied out. That default costs exactly the same -- the
     * thinking still happens and is still billed -- so declining the summary buys nothing and hides
     * where a turn's output tokens went.
     */
    fun thinkingJson(): JsonObject? = when (thinking) {
        ThinkingMode.PROVIDER_DEFAULT -> null
        ThinkingMode.OFF -> JsonObject().apply { addProperty("type", "disabled") }
        ThinkingMode.ADAPTIVE -> JsonObject().apply {
            addProperty("type", "adaptive")
            addProperty("display", "summarized")
        }
    }

    /** The request's `output_config`, or null when the effort is left to the provider. */
    fun outputConfigJson(): JsonObject? = effort.wireValue?.let { value ->
        JsonObject().apply { addProperty("effort", value) }
    }

    /**
     * The same two settings as the Responses API's single `reasoning` field, or null to leave it out.
     *
     * Worth sending rather than leaving to the endpoint, which is the opposite of the reasoning on
     * the Messages side: a deployment's own default for this field can be no reasoning at all, and a
     * current model given no reasoning budget answers a piece of agentic work by announcing what it
     * is about to do and then ending the turn -- a reply that costs a request and moves nothing
     * forward. The plugin was not sending this at all, so every OpenAI-shaped chat ran on whatever
     * the deployment happened to default to.
     *
     * [ThinkingMode.PROVIDER_DEFAULT] is still the escape hatch, and still sends nothing: this field
     * is as capable of being rejected outright by a gateway as the two Anthropic ones are.
     *
     * Only the Responses API. Chat Completions has a field of its own for this, but that protocol is
     * the one every OpenAI-compatible server speaks -- llama.cpp, vLLM, Ollama, LM Studio -- and
     * many of them answer a field they do not know with a 400. Sending it there would break working
     * setups to fix a problem they do not have.
     */
    fun reasoningJson(): JsonObject? = when (thinking) {
        ThinkingMode.PROVIDER_DEFAULT -> null
        // Not the absence of the field, which is what leaves the deployment's default standing.
        ThinkingMode.OFF -> reasoningEffort("none")
        ThinkingMode.ADAPTIVE -> reasoningEffort(effort.openAiValue ?: DEFAULT_OPENAI_EFFORT)
    }

    private fun reasoningEffort(value: String): JsonObject =
        JsonObject().apply { addProperty("effort", value) }

    companion object {

        /**
         * What thinking-on means when the effort is left to the provider.
         *
         * The two settings are one field here, so "on, at no particular level" has to resolve to a
         * level. Medium rather than the provider's own default, for the reason [Effort] gives.
         */
        private const val DEFAULT_OPENAI_EFFORT = "medium"

        /** Sends neither field, leaving both to whatever the endpoint does on its own. */
        val PROVIDER_DEFAULT = ReasoningOptions(Effort.PROVIDER_DEFAULT, ThinkingMode.PROVIDER_DEFAULT)

        fun fromSettings(): ReasoningOptions {
            val settings = AICodingAgentSettingsState.getInstance().state
            return ReasoningOptions(settings.effort, settings.thinkingMode)
        }
    }
}
