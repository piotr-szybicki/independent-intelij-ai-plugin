package com.github.piotrszybicki.independentintelijaiplugin.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The precedence behind [AgentConfiguration.fallback] -- what a project with no usable configuration
 * file runs on. Worth pinning down, because getting it wrong is invisible: requests keep working,
 * they just go somewhere other than where the user thinks they told them to.
 *
 * Note that this decides nothing for a configuration the user picked: an entry's URL is its own, and
 * the environment does not replace it. See [com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentEndpoint.from].
 */
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

    /** Blank is not an answer from either: an empty variable means unset, not "no endpoint". */
    @Test
    fun `treats blank as unset at every level`() {
        assertEquals(configured, EndpointUrl.resolve("", configured))
        assertEquals(configured, EndpointUrl.resolve("  ", configured))
    }

    /** The same fallback the configuration file's default carries, so nothing is ever unusable. */
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
