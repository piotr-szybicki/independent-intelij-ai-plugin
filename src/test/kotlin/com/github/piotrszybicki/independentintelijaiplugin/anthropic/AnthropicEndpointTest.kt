package com.github.piotrszybicki.independentintelijaiplugin.anthropic

import com.github.piotrszybicki.independentintelijaiplugin.settings.AuthScheme
import com.github.piotrszybicki.independentintelijaiplugin.settings.WireProtocol
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the two things about an endpoint that only show up as a failed request: the version header
 * going somewhere that does not understand it, and a URL pointed at a protocol other than the one
 * configured.
 */
class AnthropicEndpointTest {

    @Test
    fun `sends the version header to the Messages API`() {
        assertTrue(endpoint().headers().containsKey("anthropic-version"))
    }

    @Test
    fun `omits the version header on the OpenAI protocols`() {
        val openAi = endpoint(
            url = "https://example.openai.azure.com/openai/v1/responses",
            protocol = WireProtocol.OPENAI_RESPONSES,
        )

        assertFalse(openAi.headers().containsKey("anthropic-version"))
    }

    @Test
    fun `accepts a URL whose path matches the protocol`() {
        assertNull(endpoint().validate())
        assertNull(
            endpoint(
                url = "https://example.openai.azure.com/openai/v1/responses",
                protocol = WireProtocol.OPENAI_RESPONSES,
            ).validate(),
        )
        assertNull(
            endpoint(
                url = "https://openrouter.ai/api/v1/chat/completions",
                protocol = WireProtocol.OPENAI_CHAT_COMPLETIONS,
            ).validate(),
        )
    }

    /** The mistake this exists for: a Foundry URL left on the Messages API, which is a 400 every time. */
    @Test
    fun `rejects a responses URL configured as the Messages API`() {
        val mismatched = endpoint(url = "https://example.services.ai.azure.com/openai/v1/responses")

        assertNotNull(mismatched.validate())
    }

    @Test
    fun `rejects a messages URL configured as an OpenAI protocol`() {
        val mismatched = endpoint(protocol = WireProtocol.OPENAI_CHAT_COMPLETIONS)

        assertNotNull(mismatched.validate())
    }

    /** A gateway is free to serve any of these from a path of its own, and usually does. */
    @Test
    fun `says nothing about a path it does not recognise`() {
        assertNull(endpoint(url = "https://gateway.internal/ai/v3/complete").validate())
        assertNull(
            endpoint(
                url = "https://gateway.internal/ai/v3/complete",
                protocol = WireProtocol.OPENAI_CHAT_COMPLETIONS,
            ).validate(),
        )
    }

    private fun endpoint(
        url: String = "https://api.anthropic.com/v1/messages",
        protocol: WireProtocol = WireProtocol.ANTHROPIC_MESSAGES,
    ) = AnthropicEndpoint(
        url = url,
        token = "sk-test",
        authScheme = AuthScheme.X_API_KEY,
        protocol = protocol,
        anthropicVersion = "2023-06-01",
        extraHeaders = emptyMap(),
    )
}
