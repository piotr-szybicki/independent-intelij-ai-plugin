package com.github.piotrszybicki.independentintelijaiplugin.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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

    @Test
    fun `leaves thinking to the provider on Chat Completions`() {
        val profile = ProviderProfile.detect("https://api.openai.com/v1/chat/completions")!!

        assertEquals(ThinkingMode.PROVIDER_DEFAULT, profile.thinking)
    }

    @Test
    fun `falls back to a known host's usual API when the path says nothing`() {
        val profile = ProviderProfile.detect("https://api.anthropic.com/")!!

        assertEquals(WireProtocol.ANTHROPIC_MESSAGES, profile.protocol)
        assertEquals(AuthScheme.X_API_KEY, profile.authScheme)
    }

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

    @Test
    fun `matches hosts regardless of case or surrounding space`() {
        val profile = ProviderProfile.detect("  https://API.ANTHROPIC.COM/v1/messages  ")!!

        assertEquals(AuthScheme.X_API_KEY, profile.authScheme)
    }
}
