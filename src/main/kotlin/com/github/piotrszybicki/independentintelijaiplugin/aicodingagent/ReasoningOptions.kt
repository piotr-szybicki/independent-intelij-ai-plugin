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

    companion object {

        /** Sends neither field, leaving both to whatever the endpoint does on its own. */
        val PROVIDER_DEFAULT = ReasoningOptions(Effort.PROVIDER_DEFAULT, ThinkingMode.PROVIDER_DEFAULT)

        fun fromSettings(): ReasoningOptions {
            val settings = AICodingAgentSettingsState.getInstance().state
            return ReasoningOptions(settings.effort, settings.thinkingMode)
        }
    }
}
