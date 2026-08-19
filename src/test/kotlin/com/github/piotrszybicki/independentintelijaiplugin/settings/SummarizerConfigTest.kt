package com.github.piotrszybicki.independentintelijaiplugin.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SummarizerConfigTest {

    private fun file(section: String) = """
        {
          "summarizer": $section,
          "configurations": [
            {"name": "One", "model": "m", "url": "https://x/v1/messages", "token": "t"}
          ]
        }
    """.trimIndent()

    @Test
    fun `reads the provider, the model and the two limits`() {
        val summarizer = SummarizerConfig.parse(
            file(
                """
                {
                  "configuration": "Local Ollama",
                  "model": "qwen3-coder",
                  "max-tokens": 800,
                  "min-input-tokens": 250,
                  "thinking": "on",
                  "prompt": "Answer in British English."
                }
                """.trimIndent(),
            ),
        )

        assertEquals("Local Ollama", summarizer.configurationName)
        assertEquals("qwen3-coder", summarizer.model)
        assertEquals(800, summarizer.maxTokens)
        assertEquals(250, summarizer.minInputTokens)
        assertEquals(ThinkingMode.ADAPTIVE, summarizer.thinking)
        assertEquals("Answer in British English.", summarizer.prompt)
    }

    @Test
    fun `nothing is asked to think unless the section says so`() {
        assertEquals(ThinkingMode.OFF, SummarizerConfig.parse(file("{}")).thinking)
        assertEquals(ThinkingMode.OFF, SummarizerConfig.DEFAULT.thinking)
        assertEquals(
            ThinkingMode.PROVIDER_DEFAULT,
            SummarizerConfig.parse(file("""{"thinking": "provider-default"}""")).thinking,
        )
    }

    @Test
    fun `an empty section runs on the defaults`() {
        val summarizer = SummarizerConfig.parse(file("{}"))

        assertEquals(SummarizerConfig.DEFAULT, summarizer)
        assertEquals(SummarizerConfig.DEFAULT_MAX_TOKENS, summarizer.maxTokens)
        assertEquals(SummarizerConfig.DEFAULT_MIN_INPUT_TOKENS, summarizer.minInputTokens)
    }

    @Test
    fun `a file with no section is not an error`() {
        val without = """{"configurations": [{"name": "One", "url": "https://x/v1/messages"}]}"""

        assertEquals(SummarizerConfig.DEFAULT, SummarizerConfig.parse(without))
        assertEquals(SummarizerConfig.DEFAULT, SummarizerConfig.parse(""))
        assertEquals(SummarizerConfig.DEFAULT, SummarizerConfig.parse("""[{"name": "One"}]"""))
    }

    @Test
    fun `a blank provider means the one the chat is on`() {
        val summarizer = SummarizerConfig.parse(file("""{"configuration": "  ", "model": " haiku "}"""))

        assertEquals("", summarizer.configurationName)
        assertEquals("haiku", summarizer.model)
    }

    @Test
    fun `a floor of zero summarises every call`() {
        assertEquals(0, SummarizerConfig.parse(file("""{"min-input-tokens": 0}""")).minInputTokens)
    }

    @Test
    fun `refuses a section it cannot read`() {
        assertThrows(AgentConfigurationException::class.java) { SummarizerConfig.parse(file("\"haiku\"")) }
        assertThrows(AgentConfigurationException::class.java) {
            SummarizerConfig.parse(file("""{"model": ["a", "b"]}"""))
        }
        assertThrows(AgentConfigurationException::class.java) {
            SummarizerConfig.parse(file("""{"max-tokens": "lots"}"""))
        }
        assertThrows(AgentConfigurationException::class.java) {
            SummarizerConfig.parse(file("""{"max-tokens": 0}"""))
        }
        assertThrows(AgentConfigurationException::class.java) {
            SummarizerConfig.parse(file("""{"min-input-tokens": -1}"""))
        }
        assertThrows(AgentConfigurationException::class.java) {
            SummarizerConfig.parse(file("""{"thinking": "a little"}"""))
        }
        assertThrows(AgentConfigurationException::class.java) { SummarizerConfig.parse("model: gpt-5") }
    }

    @Test
    fun `the section survives the file being rewritten`() {
        val summarizer = SummarizerConfig(
            "Anthropic Claude",
            "claude-haiku-4-5-20251001",
            900,
            300,
            ThinkingMode.ADAPTIVE,
            "Be terse.",
        )

        val rendered = AgentConfiguration.render(
            AgentConfiguration.STARTER,
            UsageDatabaseConfig.OFF,
            FindInFilesConfig.DEFAULT,
            AgentRosterConfig.EMPTY,
            summarizer,
        )

        assertEquals(summarizer, SummarizerConfig.parse(rendered))
        assertEquals(AgentConfiguration.STARTER, AgentConfiguration.parseAll(rendered))
    }
}
