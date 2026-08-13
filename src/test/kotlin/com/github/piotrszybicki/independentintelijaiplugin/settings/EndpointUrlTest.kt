package com.github.piotrszybicki.independentintelijaiplugin.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The precedence between the three places the endpoint can be said. Worth pinning down, because
 * getting it wrong is invisible: requests keep working, they just go somewhere other than where the
 * user thinks they told them to.
 */
class EndpointUrlTest {

    private val configured = "https://saved.example/v1/messages"
    private val environment = "https://from-env.example/v1/messages"
    private val override = "https://this-session.example/v1/messages"

    @Test
    fun `the saved setting is used when nothing else is set`() {
        assertEquals(configured, EndpointUrl.resolve(null, null, configured))
    }

    @Test
    fun `the environment beats the saved setting`() {
        assertEquals(environment, EndpointUrl.resolve(null, environment, configured))
    }

    @Test
    fun `this session's override beats the environment`() {
        assertEquals(override, EndpointUrl.resolve(override, environment, configured))
    }

    /** Blank is not an answer from any of them: an empty variable means unset, not "no endpoint". */
    @Test
    fun `treats blank as unset at every level`() {
        assertEquals(configured, EndpointUrl.resolve("  ", "", configured))
        assertEquals(environment, EndpointUrl.resolve("", environment, configured))
    }

    /** The same fallback the settings page applies, so a cleared field is never an unusable one. */
    @Test
    fun `falls back to Anthropic when nothing is configured at all`() {
        assertEquals(
            AICodingAgentSettingsState.DEFAULT_ENDPOINT_URL,
            EndpointUrl.resolve(null, null, "   "),
        )
    }

    @Test
    fun `trims whatever it takes`() {
        assertEquals(environment, EndpointUrl.resolve(null, "  $environment  ", configured))
    }
}
