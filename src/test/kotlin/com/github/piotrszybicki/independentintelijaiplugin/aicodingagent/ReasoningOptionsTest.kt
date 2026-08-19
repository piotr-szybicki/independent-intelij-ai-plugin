package com.github.piotrszybicki.independentintelijaiplugin.aicodingagent

import com.github.piotrszybicki.independentintelijaiplugin.settings.Effort
import com.github.piotrszybicki.independentintelijaiplugin.settings.ThinkingMode
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningOptionsTest {

    @Test
    fun `sends nothing when both are left to the provider`() {
        val options = ReasoningOptions.PROVIDER_DEFAULT

        assertNull(options.thinkingJson())
        assertNull(options.outputConfigJson())
        assertNull(options.reasoningJson())
    }

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

    @Test
    fun `omits output_config when the effort is the provider's`() {
        val options = ReasoningOptions(Effort.PROVIDER_DEFAULT, ThinkingMode.ADAPTIVE)

        assertNull(options.outputConfigJson())
    }

    @Test
    fun `spells every effort level the way the API does`() {
        val wire = Effort.entries.mapNotNull { it.wireValue }

        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), wire)
    }

    // --- the Responses API's single field -----------------------------------------------------------

    @Test
    fun `sends the chosen effort when thinking is on`() {
        val reasoning = ReasoningOptions(Effort.MEDIUM, ThinkingMode.ADAPTIVE).reasoningJson()!!

        assertEquals("medium", reasoning.get("effort").asString)
    }

    @Test
    fun `falls back to medium when thinking is on and the effort is left to the provider`() {
        val reasoning = ReasoningOptions(Effort.PROVIDER_DEFAULT, ThinkingMode.ADAPTIVE).reasoningJson()!!

        assertEquals("medium", reasoning.get("effort").asString)
    }

    @Test
    fun `asks for no reasoning rather than omitting the field when thinking is off`() {
        val reasoning = ReasoningOptions(Effort.HIGH, ThinkingMode.OFF).reasoningJson()!!

        assertEquals("none", reasoning.get("effort").asString)
    }

    @Test
    fun `clamps the levels above high to what OpenAI accepts`() {
        val xhigh = ReasoningOptions(Effort.XHIGH, ThinkingMode.ADAPTIVE).reasoningJson()!!
        val max = ReasoningOptions(Effort.MAX, ThinkingMode.ADAPTIVE).reasoningJson()!!

        assertEquals("high", xhigh.get("effort").asString)
        assertEquals("high", max.get("effort").asString)
    }

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
            reasoning = ReasoningOptions(Effort.MEDIUM, ThinkingMode.ADAPTIVE).reasoningJson(),
        )

        assertEquals("medium", body.getAsJsonObject("reasoning").get("effort").asString)
    }

    @Test
    fun `leaves the field out of the body when there is nothing to say`() {
        val body = OpenAiProtocol.responsesRequest(
            "gpt-5.3-codex", 8000, null, listOf(ChatMessage.text("user", "hi")), emptyList(),
            reasoning = null,
        )

        assertFalse(body.has("reasoning"))
    }
}
