package com.github.piotrszybicki.independentintelijaiplugin.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConfigurationTest {

    private val minimal = """
        {
          "configurations": [
            {
              "name": "Anthropic",
              "model": "claude-sonnet-5",
              "url": "https://api.anthropic.com/v1/messages",
              "token": "${'$'}MY_TOKEN"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `reads the four fields a configuration needs`() {
        val configuration = AgentConfiguration.parseAll(minimal).single()

        assertEquals("Anthropic", configuration.name)
        assertEquals("claude-sonnet-5", configuration.model)
        assertEquals("https://api.anthropic.com/v1/messages", configuration.url)
        assertEquals("MY_TOKEN", configuration.tokenEnvVar)
    }

    @Test
    fun `fills the header and protocol in from the URL when they are not given`() {
        val configuration = AgentConfiguration.parseAll(minimal).single()

        assertEquals(AuthScheme.X_API_KEY, configuration.authScheme)
        assertEquals(WireProtocol.ANTHROPIC_MESSAGES, configuration.protocol)
        assertEquals(AgentConfiguration.DEFAULT_API_VERSION, configuration.apiVersion)
    }

    @Test
    fun `a configuration with one model offers a list of one`() {
        assertEquals(listOf("claude-sonnet-5"), AgentConfiguration.parseAll(minimal).single().models)
    }

    @Test
    fun `reads the model list and takes its first entry as the default`() {
        val configuration = AgentConfiguration.parseAll(
            """
            [{
              "name": "One", "url": "https://x/v1/messages", "token": "t",
              "models": ["claude-sonnet-5", "claude-opus-5"]
            }]
            """.trimIndent(),
        ).single()

        assertEquals("claude-sonnet-5", configuration.model)
        assertEquals(listOf("claude-sonnet-5", "claude-opus-5"), configuration.models)
    }

    @Test
    fun `keeps a default that is not in the list, at the front of it`() {
        val configuration = AgentConfiguration.parseAll(
            """
            [{
              "name": "One", "model": "gpt-5.6-sol", "url": "https://x/v1/responses", "token": "t",
              "models": ["gpt-5", "gpt-5-mini"]
            }]
            """.trimIndent(),
        ).single()

        assertEquals("gpt-5.6-sol", configuration.model)
        assertEquals(listOf("gpt-5.6-sol", "gpt-5", "gpt-5-mini"), configuration.models)
    }

    @Test
    fun `refuses a configuration with neither a model nor a list`() {
        assertThrows(AgentConfigurationException::class.java) {
            AgentConfiguration.parseAll("""[{"name": "One", "url": "https://x/v1/messages", "token": "t"}]""")
        }
    }

    @Test
    fun `selects a model only when the configuration offers it`() {
        val configuration = AgentConfiguration.parseAll(
            """
            [{
              "name": "One", "url": "https://x/v1/messages", "token": "t",
              "models": ["claude-sonnet-5", "claude-opus-5"]
            }]
            """.trimIndent(),
        ).single()

        assertEquals("claude-opus-5", configuration.withModel("claude-opus-5").model)
        assertEquals("claude-sonnet-5", configuration.withModel("gpt-5").model)
        assertEquals("claude-sonnet-5", configuration.withModel("").model)
    }

    @Test
    fun `defaults the model parameters when they are not given`() {
        val configuration = AgentConfiguration.parseAll(minimal).single()

        assertEquals(ThinkingMode.ADAPTIVE, configuration.thinking)
        assertEquals(Effort.MEDIUM, configuration.effort)
        assertEquals(AgentConfiguration.DEFAULT_MAX_TOKENS, configuration.maxTokens)
        assertEquals(AgentConfiguration.DEFAULT_CONTEXT_WINDOW, configuration.contextWindowTokens)
        assertEquals(AgentConfiguration.DEFAULT_REQUEST_TIMEOUT_SECONDS, configuration.requestTimeoutSeconds)
    }

    @Test
    fun `defaults thinking to the provider on a protocol that cannot carry it`() {
        val configuration = AgentConfiguration.parseAll(
            """
            [{"name": "Local", "model": "m", "url": "http://localhost:11434/v1/chat/completions", "token": "t"}]
            """.trimIndent(),
        ).single()

        assertEquals(ThinkingMode.PROVIDER_DEFAULT, configuration.thinking)
    }

    @Test
    fun `reads the model parameters when they are given`() {
        val configuration = AgentConfiguration.parseAll(
            """
            [{
              "name": "One", "model": "m", "url": "https://x/v1/messages", "token": "t",
              "thinking": "off", "effort": "xhigh", "max-tokens": 32000, "context-window": 0
            }]
            """.trimIndent(),
        ).single()

        assertEquals(ThinkingMode.OFF, configuration.thinking)
        assertEquals(Effort.XHIGH, configuration.effort)
        assertEquals(32000, configuration.maxTokens)
        assertEquals(0, configuration.contextWindowTokens)
    }

    @Test
    fun `reads the request timeout when it is given`() {
        val configuration = AgentConfiguration.parseAll(
            """
            [{
              "name": "Slow", "model": "m", "url": "https://x/v1/messages", "token": "t",
              "request-timeout-seconds": 300
            }]
            """.trimIndent(),
        ).single()

        assertEquals(300, configuration.requestTimeoutSeconds)
    }

    @Test
    fun `refuses a request timeout below one second`() {
        val zero = """
            [{
              "name": "One", "model": "m", "url": "https://x/v1/messages", "token": "t",
              "request-timeout-seconds": 0
            }]
        """.trimIndent()

        assertThrows(AgentConfigurationException::class.java) { AgentConfiguration.parseAll(zero) }
    }

    @Test
    fun `refuses a reply limit below one`() {
        val zero = """
            [{"name": "One", "model": "m", "url": "https://x/v1/messages", "token": "t", "max-tokens": 0}]
        """.trimIndent()

        assertThrows(AgentConfigurationException::class.java) { AgentConfiguration.parseAll(zero) }
    }

    @Test
    fun `refuses a thinking value it does not know`() {
        val unknown = """
            [{"name": "One", "model": "m", "url": "https://x/v1/messages", "token": "t", "thinking": "sometimes"}]
        """.trimIndent()

        assertThrows(AgentConfigurationException::class.java) { AgentConfiguration.parseAll(unknown) }
    }

    @Test
    fun `reads thinking written as a boolean`() {
        val configuration = AgentConfiguration.parseAll(
            """
            [{"name": "One", "model": "m", "url": "https://x/v1/messages", "token": "t", "thinking": false}]
            """.trimIndent(),
        ).single()

        assertEquals(ThinkingMode.OFF, configuration.thinking)
    }

    @Test
    fun `reads the customizations section`() {
        val configuration = AgentConfiguration.parseAll(
            """
            {
              "configurations": [
                {
                  "name": "Gateway",
                  "model": "claude-sonnet-5",
                  "url": "https://gateway.internal/v1/messages",
                  "token": "plain-text-token",
                  "header-type": "Authorization",
                  "additional-customizations": {
                    "anthropic-version": "2024-01-01",
                    "extra-headers": { "X-Tenant": "acme" }
                  }
                }
              ]
            }
            """.trimIndent(),
        ).single()

        assertEquals(AuthScheme.BEARER, configuration.authScheme)
        assertEquals("2024-01-01", configuration.apiVersion)
        assertEquals(mapOf("X-Tenant" to "acme"), configuration.extraHeaders)
    }

    @Test
    fun `an empty anthropic-version means the header is not sent`() {
        val configuration = AgentConfiguration.parseAll(
            """
            [
              {
                "name": "Gateway",
                "model": "claude-sonnet-5",
                "url": "https://gateway.internal/v1/messages",
                "token": "t",
                "additional-customizations": { "anthropic-version": "" }
              }
            ]
            """.trimIndent(),
        ).single()

        assertEquals("", configuration.apiVersion)
    }

    @Test
    fun `a token starting with a dollar names an environment variable`() {
        assertEquals("AI_API_KEY", AgentConfiguration.envVarName("\$AI_API_KEY"))
        assertEquals("AI_API_KEY", AgentConfiguration.envVarName("\${AI_API_KEY}"))
        assertEquals("AI_API_KEY", AgentConfiguration.envVarName("  \$AI_API_KEY  "))
    }

    @Test
    fun `anything else is the token itself`() {
        assertNull(AgentConfiguration.envVarName("sk-ant-0123"))
        assertNull(AgentConfiguration.envVarName(""))
        assertNull(AgentConfiguration.envVarName("$"))
    }

    @Test
    fun `an unset variable leaves the token blank`() {
        val configuration = AgentConfiguration.parseAll(minimal).single()

        assertEquals("", configuration.resolvedToken)
    }

    @Test
    fun `a plain token is used as written`() {
        val configuration = AgentConfiguration.DEFAULT.copy(token = "sk-ant-0123")

        assertNull(configuration.tokenEnvVar)
        assertEquals("sk-ant-0123", configuration.resolvedToken)
    }

    @Test
    fun `reads a bare array as well as the wrapper`() {
        val bare = """
            [{"name": "One", "model": "m", "url": "https://x/v1/messages", "token": "t"}]
        """.trimIndent()

        assertEquals("One", AgentConfiguration.parseAll(bare).single().name)
    }

    @Test
    fun `refuses a configuration with no name`() {
        val error = assertThrows(AgentConfigurationException::class.java) {
            AgentConfiguration.parseAll("""[{"model": "m", "url": "https://x/v1/messages"}]""")
        }

        assertTrue(error.message!!.contains("name"))
    }

    @Test
    fun `refuses two configurations with the same name`() {
        val duplicated = """
            [
              {"name": "One", "model": "m", "url": "https://x/v1/messages", "token": "t"},
              {"name": "One", "model": "m", "url": "https://y/v1/messages", "token": "t"}
            ]
        """.trimIndent()

        assertThrows(AgentConfigurationException::class.java) { AgentConfiguration.parseAll(duplicated) }
    }

    @Test
    fun `refuses a header type it does not know`() {
        val unknown = """
            [{"name": "One", "model": "m", "url": "https://x/v1/messages", "token": "t", "header-type": "x-token"}]
        """.trimIndent()

        assertThrows(AgentConfigurationException::class.java) { AgentConfiguration.parseAll(unknown) }
    }

    @Test
    fun `refuses text that is not JSON`() {
        assertThrows(AgentConfigurationException::class.java) { AgentConfiguration.parseAll("model: gpt-5") }
    }

    @Test
    fun `the starter file parses back into the configurations it was written from`() {
        val rendered = AgentConfiguration.render(AgentConfiguration.STARTER)

        assertEquals(AgentConfiguration.STARTER, AgentConfiguration.parseAll(rendered))
    }

    @Test
    fun `the selection falls back to the first entry when the saved name is gone`() {
        val configurations = AgentConfiguration.STARTER

        assertEquals(configurations[1], AgentConfigurations.select(configurations, configurations[1].name))
        assertEquals(configurations[0], AgentConfigurations.select(configurations, "deleted"))
        assertNull(AgentConfigurations.select(emptyList(), "anything"))
    }

    @Test
    fun `header types are read by header name, enum name or alias`() {
        assertEquals(AuthScheme.BEARER, AuthScheme.parse("Authorization"))
        assertEquals(AuthScheme.BEARER, AuthScheme.parse("bearer"))
        assertEquals(AuthScheme.BEARER, AuthScheme.parse("BEARER"))
        assertEquals(AuthScheme.X_API_KEY, AuthScheme.parse("x-api-key"))
        assertEquals(AuthScheme.API_KEY, AuthScheme.parse("api-key"))
        assertNull(AuthScheme.parse("x-token"))
    }

    @Test
    fun `protocols are read by wire name or short form, but never ambiguously`() {
        assertEquals(WireProtocol.OPENAI_RESPONSES, WireProtocol.parse("openai-responses"))
        assertEquals(WireProtocol.OPENAI_RESPONSES, WireProtocol.parse("responses"))
        assertEquals(WireProtocol.OPENAI_CHAT_COMPLETIONS, WireProtocol.parse("chat_completions"))
        assertEquals(WireProtocol.ANTHROPIC_MESSAGES, WireProtocol.parse("anthropic"))
        assertNull(WireProtocol.parse("openai"))
    }

    @Test
    fun `every thinking and effort value round-trips through its file name`() {
        ThinkingMode.entries.forEach { assertEquals(it, ThinkingMode.parse(it.fileName)) }
        Effort.entries.forEach { assertEquals(it, Effort.parse(it.fileName)) }

        assertEquals(ThinkingMode.ADAPTIVE, ThinkingMode.parse("adaptive"))
        assertEquals(ThinkingMode.PROVIDER_DEFAULT, ThinkingMode.parse("default"))
        assertEquals(Effort.PROVIDER_DEFAULT, Effort.parse("default"))
        assertNull(Effort.parse("maximum"))
    }
}
