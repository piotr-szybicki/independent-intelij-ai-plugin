package com.github.piotrszybicki.independentintelijaiplugin.aicodingagent

import com.github.piotrszybicki.independentintelijaiplugin.settings.Effort
import com.github.piotrszybicki.independentintelijaiplugin.settings.ThinkingMode
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers what each setting turns into on the wire.
 *
 * All of it fails quietly: a field spelled wrong or sent to an endpoint that does not know it comes
 * back as a 400 rather than a wrong answer, and a field left out entirely is worse -- the request
 * succeeds and is billed at whatever the provider's own default costs.
 */
class ReasoningOptionsTest {

    @Test
    fun `sends nothing when both are left to the provider`() {
        val options = ReasoningOptions.PROVIDER_DEFAULT

        assertNull(options.thinkingJson())
        assertNull(options.outputConfigJson())
        assertNull(options.reasoningJson())
    }

    /** Thinking is billed the same whether or not the summary is asked for, so it is always asked for. */
    @Test
    fun `asks for the summary whenever thinking is on`() {
        val options = ReasoningOptions(Effort.MEDIUM, ThinkingMode.ADAPTIVE)

        val thinking = options.thinkingJson()!!
        assertEquals("adaptive", thinking.get("type").asString)
        assertEquals("summarized", thinking.get("display").asString)
    }

    @Test
    fun `disables thinking outright`() {
        val options = ReasoningOptions(Effort.MEDIUM, ThinkingMode.OFF)

        val thinking = options.thinkingJson()!!
        assertEquals("disabled", thinking.get("type").asString)
        // No display: there is nothing to summarise, and the field is rejected alongside disabled.
        assertNull(thinking.get("display"))
    }

    @Test
    fun `wraps the effort in output_config`() {
        val options = ReasoningOptions(Effort.MEDIUM, ThinkingMode.ADAPTIVE)

        assertEquals("medium", options.outputConfigJson()!!.get("effort").asString)
    }

    /** The escape hatch for the older models, which reject the field rather than ignoring it. */
    @Test
    fun `omits output_config when the effort is the provider's`() {
        val options = ReasoningOptions(Effort.PROVIDER_DEFAULT, ThinkingMode.ADAPTIVE)

        assertNull(options.outputConfigJson())
    }

    /** Every level the settings offer has to be a value the API knows; a typo here is a 400. */
    @Test
    fun `spells every effort level the way the API does`() {
        val wire = Effort.entries.mapNotNull { it.wireValue }

        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), wire)
    }

    // --- the Responses API's single field -----------------------------------------------------------

    /**
     * The one field whose absence is not neutral: a deployment left to its own default can run a
     * current model at no reasoning at all, which shows up as a turn that announces a tool call
     * instead of making one -- an ordinary finished turn as far as the response is concerned, and a
     * chat that looks like it gave up as far as the user is concerned.
     */
    @Test
    fun `sends the chosen effort when thinking is on`() {
        val reasoning = ReasoningOptions(Effort.MEDIUM, ThinkingMode.ADAPTIVE).reasoningJson()!!

        assertEquals("medium", reasoning.get("effort").asString)
    }

    /** The two settings are one field here, so thinking-on has to resolve to some level. */
    @Test
    fun `falls back to medium when thinking is on and the effort is left to the provider`() {
        val reasoning = ReasoningOptions(Effort.PROVIDER_DEFAULT, ThinkingMode.ADAPTIVE).reasoningJson()!!

        assertEquals("medium", reasoning.get("effort").asString)
    }

    /** Off is a level of its own here, not the absence of the field -- absent leaves the default. */
    @Test
    fun `asks for no reasoning rather than omitting the field when thinking is off`() {
        val reasoning = ReasoningOptions(Effort.HIGH, ThinkingMode.OFF).reasoningJson()!!

        assertEquals("none", reasoning.get("effort").asString)
    }

    /**
     * Anthropic's scale runs two levels past OpenAI's, and a level OpenAI does not know is a
     * rejected request rather than a clamped one.
     */
    @Test
    fun `clamps the levels above high to what OpenAI accepts`() {
        val xhigh = ReasoningOptions(Effort.XHIGH, ThinkingMode.ADAPTIVE).reasoningJson()!!
        val max = ReasoningOptions(Effort.MAX, ThinkingMode.ADAPTIVE).reasoningJson()!!

        assertEquals("high", xhigh.get("effort").asString)
        assertEquals("high", max.get("effort").asString)
    }

    /** Guards the assumption the request builder makes: an empty object is never sent. */
    @Test
    fun `never renders an empty reasoning object`() {
        ThinkingMode.entries.forEach { thinking ->
            Effort.entries.forEach { effort ->
                val reasoning: JsonObject? = ReasoningOptions(effort, thinking).reasoningJson()
                assertTrue(
                    "an empty reasoning object would read as a request for the provider's default",
                    reasoning == null || reasoning.has("effort"),
                )
            }
        }
    }

    @Test
    fun `carries the field into the Responses request body`() {
        val body = OpenAiProtocol.responsesRequest(
            "gpt-5.3-codex", 8000, null, listOf(ChatMessage.text("user", "hi")), emptyList(),
            ReasoningOptions(Effort.MEDIUM, ThinkingMode.ADAPTIVE).reasoningJson(),
        )

        assertEquals("medium", body.getAsJsonObject("reasoning").get("effort").asString)
    }

    @Test
    fun `leaves the field out of the body when there is nothing to say`() {
        val body = OpenAiProtocol.responsesRequest(
            "gpt-5.3-codex", 8000, null, listOf(ChatMessage.text("user", "hi")), emptyList(), null,
        )

        assertFalse(body.has("reasoning"))
    }
}
