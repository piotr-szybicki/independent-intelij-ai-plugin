package com.github.piotrszybicki.independentintelijaiplugin.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The section decides which searches never run, so both halves are pinned down here: what a phrase
 * in the list stops, and -- just as important -- what it does not. A block that quietly widened into
 * substring matching would refuse searches nobody blocked, from inside a chat with no way to see
 * why.
 */
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

    /** A longer query containing a blocked word is the narrower search being asked for. */
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

    /** Blanks would block nothing and duplicates are a line read twice, so neither survives. */
    @Test
    fun `drops blank and repeated phrases`() {
        val config = FindInFilesConfig.parse(file("""{"blocked-phrases": ["public", "  ", "public", ""]}"""))

        assertEquals(listOf("public"), config.blockedPhrases)
        assertNull(config.blocking(""))
        assertNull(config.blocking("   "))
    }

    /** The file predates the section, and a project that blocks nothing never writes one. */
    @Test
    fun `a file with no section blocks nothing and is not an error`() {
        val without = """{"configurations": [{"name": "One", "url": "https://x/v1/messages"}]}"""

        assertEquals(FindInFilesConfig.DEFAULT, FindInFilesConfig.parse(without))
        assertEquals(FindInFilesConfig.DEFAULT, FindInFilesConfig.parse(""))
        // The bare-array form parseAll also accepts has nowhere to put a section.
        assertEquals(FindInFilesConfig.DEFAULT, FindInFilesConfig.parse("""[{"name": "One"}]"""))
        assertEquals(FindInFilesConfig.DEFAULT, FindInFilesConfig.parse(file("{}")))
    }

    /** Reported rather than ignored: a list read as empty is a block that stopped blocking. */
    @Test
    fun `refuses a section it cannot read`() {
        // The list written straight into the section rather than under its field.
        assertThrows(AgentConfigurationException::class.java) { FindInFilesConfig.parse(file("""["public"]""")) }
        assertThrows(AgentConfigurationException::class.java) {
            FindInFilesConfig.parse(file("""{"blocked-phrases": "public"}"""))
        }
        assertThrows(AgentConfigurationException::class.java) {
            FindInFilesConfig.parse(file("""{"blocked-phrases": [{"phrase": "public"}]}"""))
        }
        assertThrows(AgentConfigurationException::class.java) { FindInFilesConfig.parse("model: gpt-5") }
    }

    /**
     * **Fill In Defaults** rewrites the whole file, so the section has to survive a round trip
     * through [AgentConfiguration.render] -- a list that disappeared when an unrelated button was
     * pressed would silently unblock every phrase in it.
     */
    @Test
    fun `the section survives the file being rewritten`() {
        val config = FindInFilesConfig(listOf("public", "import"))

        val rendered = AgentConfiguration.render(AgentConfiguration.STARTER, UsageDatabaseConfig.OFF, config)

        assertEquals(config, FindInFilesConfig.parse(rendered))
        assertEquals(UsageDatabaseConfig.OFF, UsageDatabaseConfig.parse(rendered))
        assertEquals(AgentConfiguration.STARTER, AgentConfiguration.parseAll(rendered))
    }
}
