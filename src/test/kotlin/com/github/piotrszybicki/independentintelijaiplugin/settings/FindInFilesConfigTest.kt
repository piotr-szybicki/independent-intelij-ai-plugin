package com.github.piotrszybicki.independentintelijaiplugin.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class FindInFilesConfigTest {

    private fun file(section: String) = """
        {
          "find-in-files": $section,
          "configurations": [
            {"name": "One", "model": "m", "url": "https://x/v1/messages", "token": "t"}
          ]
        }
    """.trimIndent()

    @Test
    fun `reads the phrases in the order they are written`() {
        val config = FindInFilesConfig.parse(file("""{"blocked-phrases": ["public", "fun"]}"""))

        assertEquals(listOf("public", "fun"), config.blockedPhrases)
    }

    @Test
    fun `blocks the whole query, whatever its case or spacing`() {
        val config = FindInFilesConfig.parse(file("""{"blocked-phrases": ["public"]}"""))

        assertEquals("public", config.blocking("public"))
        assertEquals("public", config.blocking("PUBLIC"))
        assertEquals("public", config.blocking("  public  "))
    }

    @Test
    fun `does not block a query that merely contains a blocked phrase`() {
        val config = FindInFilesConfig.parse(file("""{"blocked-phrases": ["get"]}"""))

        assertNull(config.blocking("getUserConfiguration"))
        assertNull(config.blocking("val get ="))
        assertNull(config.blocking("forget"))
    }

    @Test
    fun `an empty list blocks nothing`() {
        assertNull(FindInFilesConfig.parse(file("""{"blocked-phrases": []}""")).blocking("public"))
        assertNull(FindInFilesConfig.DEFAULT.blocking("public"))
    }

    @Test
    fun `drops blank and repeated phrases`() {
        val config = FindInFilesConfig.parse(file("""{"blocked-phrases": ["public", "  ", "public", ""]}"""))

        assertEquals(listOf("public"), config.blockedPhrases)
        assertNull(config.blocking(""))
        assertNull(config.blocking("   "))
    }

    @Test
    fun `a file with no section blocks nothing and is not an error`() {
        val without = """{"configurations": [{"name": "One", "url": "https://x/v1/messages"}]}"""

        assertEquals(FindInFilesConfig.DEFAULT, FindInFilesConfig.parse(without))
        assertEquals(FindInFilesConfig.DEFAULT, FindInFilesConfig.parse(""))
        assertEquals(FindInFilesConfig.DEFAULT, FindInFilesConfig.parse("""[{"name": "One"}]"""))
        assertEquals(FindInFilesConfig.DEFAULT, FindInFilesConfig.parse(file("{}")))
    }

    @Test
    fun `refuses a section it cannot read`() {
        assertThrows(AgentConfigurationException::class.java) { FindInFilesConfig.parse(file("""["public"]""")) }
        assertThrows(AgentConfigurationException::class.java) {
            FindInFilesConfig.parse(file("""{"blocked-phrases": "public"}"""))
        }
        assertThrows(AgentConfigurationException::class.java) {
            FindInFilesConfig.parse(file("""{"blocked-phrases": [{"phrase": "public"}]}"""))
        }
        assertThrows(AgentConfigurationException::class.java) { FindInFilesConfig.parse("model: gpt-5") }
    }

    @Test
    fun `the section survives the file being rewritten`() {
        val config = FindInFilesConfig(listOf("public", "import"))

        val rendered = AgentConfiguration.render(AgentConfiguration.STARTER, UsageDatabaseConfig.OFF, config)

        assertEquals(config, FindInFilesConfig.parse(rendered))
        assertEquals(UsageDatabaseConfig.OFF, UsageDatabaseConfig.parse(rendered))
        assertEquals(AgentConfiguration.STARTER, AgentConfiguration.parseAll(rendered))
    }
}
