package com.github.piotrszybicki.independentintelijaiplugin.aicodingagent

import com.github.piotrszybicki.independentintelijaiplugin.settings.Effort
import com.github.piotrszybicki.independentintelijaiplugin.settings.ThinkingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ReasoningOptionsTest {
    @Test
    fun `sends nothing when both are left to the provider`() {
        val options = ReasoningOptions.PROVIDER_DEFAULT

        assertNull(options.thinkingJson())
        assertNull(options.outputConfigJson())
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

    @Test
    fun `omits reasoning from the Responses request`() {
        val body =
            OpenAiProtocol.responsesRequest(
                "gpt-5.3-codex",
                8000,
                null,
                listOf(ChatMessage.text("user", "hi")),
                emptyList(),
            )
        assertFalse(body.has("reasoning"))
    }
}
