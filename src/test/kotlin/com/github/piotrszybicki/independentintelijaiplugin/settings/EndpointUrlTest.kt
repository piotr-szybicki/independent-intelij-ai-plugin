package com.github.piotrszybicki.independentintelijaiplugin.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class EndpointUrlTest {

    private val configured = "https://saved.example/v1/messages"
    private val environment = "https://from-env.example/v1/messages"

    @Test
    fun `the built-in default is used when the environment says nothing`() {
        assertEquals(configured, EndpointUrl.resolve(null, configured))
    }

    @Test
    fun `the environment beats the built-in default`() {
        assertEquals(environment, EndpointUrl.resolve(environment, configured))
    }

    @Test
    fun `treats blank as unset at every level`() {
        assertEquals(configured, EndpointUrl.resolve("", configured))
        assertEquals(configured, EndpointUrl.resolve("  ", configured))
    }

    @Test
    fun `falls back to Anthropic when nothing is configured at all`() {
        assertEquals(
            AgentConfiguration.DEFAULT_ENDPOINT_URL,
            EndpointUrl.resolve(null, "   "),
        )
    }

    @Test
    fun `trims whatever it takes`() {
        assertEquals(environment, EndpointUrl.resolve("  $environment  ", configured))
    }
}
