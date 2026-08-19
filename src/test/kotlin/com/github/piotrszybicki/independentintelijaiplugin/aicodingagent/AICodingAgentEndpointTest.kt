package com.github.piotrszybicki.independentintelijaiplugin.aicodingagent

import com.github.piotrszybicki.independentintelijaiplugin.settings.AuthScheme
import com.github.piotrszybicki.independentintelijaiplugin.settings.WireProtocol
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AICodingAgentEndpointTest {

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
    ) = AICodingAgentEndpoint(
        url = url,
        token = "sk-test",
        authScheme = AuthScheme.X_API_KEY,
        protocol = protocol,
        apiVersion = "2023-06-01",
        extraHeaders = emptyMap(),
    )
}
