package com.github.piotrszybicki.independentintelijaiplugin.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the settings page reads out of an endpoint URL.
 *
 * Worth pinning down in both directions: getting a detection wrong silently reconfigures a working
 * endpoint the moment its URL is touched, and failing to detect one leaves the user assembling the
 * same three settings by hand that the URL already spelled out.
 */
class ProviderProfileTest {

    @Test
    fun `reads Anthropic's own endpoint`() {
        val profile = ProviderProfile.detect("https://api.anthropic.com/v1/messages")!!

        assertEquals(WireProtocol.ANTHROPIC_MESSAGES, profile.protocol)
        assertEquals(AuthScheme.X_API_KEY, profile.authScheme)
        assertEquals(ThinkingMode.ADAPTIVE, profile.thinking)
    }

    @Test
    fun `reads a Foundry deployment as bearer-authenticated Responses`() {
        val profile = ProviderProfile.detect("https://my-resource.services.ai.azure.com/openai/v1/responses")!!

        assertEquals(WireProtocol.OPENAI_RESPONSES, profile.protocol)
        assertEquals(AuthScheme.BEARER, profile.authScheme)
        assertEquals(ThinkingMode.ADAPTIVE, profile.thinking)
    }

    @Test
    fun `takes the protocol from the path when a known host serves more than one API`() {
        val profile = ProviderProfile.detect("https://my-resource.openai.azure.com/openai/v1/chat/completions")!!

        assertEquals(WireProtocol.OPENAI_CHAT_COMPLETIONS, profile.protocol)
        assertEquals(AuthScheme.BEARER, profile.authScheme)
    }

    /**
     * The setting would otherwise read as applied and do nothing: no thinking field is sent on this
     * protocol at all, because too many of the servers that speak it reject one.
     */
    @Test
    fun `leaves thinking to the provider on Chat Completions`() {
        val profile = ProviderProfile.detect("https://api.openai.com/v1/chat/completions")!!

        assertEquals(ThinkingMode.PROVIDER_DEFAULT, profile.thinking)
    }

    /** A host with no path to go on still resolves, so a half-typed URL settles somewhere sensible. */
    @Test
    fun `falls back to a known host's usual API when the path says nothing`() {
        val profile = ProviderProfile.detect("https://api.anthropic.com/")!!

        assertEquals(WireProtocol.ANTHROPIC_MESSAGES, profile.protocol)
        assertEquals(AuthScheme.X_API_KEY, profile.authScheme)
    }

    /**
     * The one thing discovery must not guess at. A gateway is free to want either header, and
     * overwriting the user's answer would break a setup that works.
     */
    @Test
    fun `says nothing about the token header of an unrecognised host`() {
        val profile = ProviderProfile.detect("https://gateway.internal.example/v1/responses")!!

        assertEquals(WireProtocol.OPENAI_RESPONSES, profile.protocol)
        assertNull(profile.authScheme)
    }

    @Test
    fun `detects nothing when neither the host nor the path says anything`() {
        assertNull(ProviderProfile.detect("https://gateway.internal.example/v1/generate"))
        assertNull(ProviderProfile.detect("not a url at all"))
        assertNull(ProviderProfile.detect(""))
    }

    /** Hosts arrive from a text field, so nothing may depend on how they were typed. */
    @Test
    fun `matches hosts regardless of case or surrounding space`() {
        val profile = ProviderProfile.detect("  https://API.ANTHROPIC.COM/v1/messages  ")!!

        assertEquals(AuthScheme.X_API_KEY, profile.authScheme)
    }
}
