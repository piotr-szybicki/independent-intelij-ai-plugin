package com.github.piotrszybicki.independentintelijaiplugin.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The file is hand-edited and decides where every request goes, so what it accepts and what it
 * refuses are both worth pinning down: a field silently read as something else is a chat running
 * against the wrong provider, which nothing downstream would report as a mistake.
 */
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

    /** The URL says the rest, so an entry that leaves them out is still a complete one. */
    @Test
    fun `fills the header and protocol in from the URL when they are not given`() {
        val configuration = AgentConfiguration.parseAll(minimal).single()

        assertEquals(AuthScheme.X_API_KEY, configuration.authScheme)
        assertEquals(WireProtocol.ANTHROPIC_MESSAGES, configuration.protocol)
        assertEquals(AgentConfiguration.DEFAULT_API_VERSION, configuration.apiVersion)
    }

    @Test
    fun `defaults the model parameters when they are not given`() {
        val configuration = AgentConfiguration.parseAll(minimal).single()

        assertEquals(ThinkingMode.ADAPTIVE, configuration.thinking)
        assertEquals(Effort.MEDIUM, configuration.effort)
        assertEquals(AgentConfiguration.DEFAULT_MAX_TOKENS, configuration.maxTokens)
        assertEquals(AgentConfiguration.DEFAULT_CONTEXT_WINDOW, configuration.contextWindowTokens)
    }

    /**
     * Nothing is sent on Chat Completions whatever the field says, so defaulting it to on there
     * would be a value that reads as applied and does nothing.
     */
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
        // Zero is a real answer here -- it switches compaction off rather than meaning "unset".
        assertEquals(0, configuration.contextWindowTokens)
    }

    /** A reply cap of zero is a chat that cannot answer, so it is reported rather than replaced. */
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

    /** Written as a JSON boolean it reads the way it looks, which is how someone will write it. */
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

    /** Present and empty is how the file says "leave the header off", which some gateways need. */
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
        // A lone dollar names nothing, so it is not treated as a variable reference.
        assertNull(AgentConfiguration.envVarName("$"))
    }

    /** A variable that is not set reads as blank, which is what turns into a message naming it. */
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

    /** What is left after copying the array out of a larger file, so it is read the same way. */
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

    /** What the starter file is written from, so the file created on first open has to read back. */
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

    /** "openai" alone is two of the three, and guessing between them is a 400 every time. */
    @Test
    fun `protocols are read by wire name or short form, but never ambiguously`() {
        assertEquals(WireProtocol.OPENAI_RESPONSES, WireProtocol.parse("openai-responses"))
        assertEquals(WireProtocol.OPENAI_RESPONSES, WireProtocol.parse("responses"))
        assertEquals(WireProtocol.OPENAI_CHAT_COMPLETIONS, WireProtocol.parse("chat_completions"))
        assertEquals(WireProtocol.ANTHROPIC_MESSAGES, WireProtocol.parse("anthropic"))
        assertNull(WireProtocol.parse("openai"))
    }

    /** Every name the file writes has to read back, or the starter file would not survive itself. */
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
