package com.github.piotrszybicki.independentintelijaiplugin.aicodingagent

import com.github.piotrszybicki.independentintelijaiplugin.settings.Effort
import com.github.piotrszybicki.independentintelijaiplugin.settings.ThinkingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
