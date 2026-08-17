package com.github.piotrszybicki.independentintelijaiplugin.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The section decides whether anything is recorded at all, and nothing downstream reports it when it
 * is not: a request that goes unrecorded looks exactly like a request. So both halves are pinned
 * down here -- what the section means when it is there, and that a file without one is silence
 * rather than an error.
 */
class UsageDatabaseConfigTest {

    private val url = "jdbc:mysql://localhost:3306/ai_usage?user=root&password=\${env:MYSQL_PASSWORD}"

    private fun file(section: String) = """
        {
          "usage-database": $section,
          "configurations": [
            {"name": "One", "model": "m", "url": "https://x/v1/messages", "token": "t"}
          ]
        }
    """.trimIndent()

    @Test
    fun `reads the url and the switch`() {
        val database = UsageDatabaseConfig.parse(file("""{"url": "$url", "enabled": true}"""))

        assertEquals(url, database.url)
        assertTrue(database.enabled)
        assertTrue(database.isActive)
    }

    /** Absent means on, so a section that is only a URL records rather than sitting there quietly. */
    @Test
    fun `the switch defaults to on`() {
        assertTrue(UsageDatabaseConfig.parse(file("""{"url": "$url"}""")).enabled)
    }

    @Test
    fun `switched off keeps the url and records nothing`() {
        val database = UsageDatabaseConfig.parse(file("""{"url": "$url", "enabled": false}"""))

        assertEquals(url, database.url)
        assertFalse(database.isActive)
    }

    /** On but pointed at nothing is the starter file, and it has to mean "record nothing". */
    @Test
    fun `an empty url records nothing however the switch is set`() {
        assertFalse(UsageDatabaseConfig.parse(file("""{"url": "", "enabled": true}""")).isActive)
        assertFalse(UsageDatabaseConfig.OFF.isActive)
    }

    /** Written as a JSON boolean, but a quoted one is what it looks like and reads the same way. */
    @Test
    fun `the switch is read from a quoted boolean too`() {
        assertFalse(UsageDatabaseConfig.parse(file("""{"url": "$url", "enabled": "false"}""")).enabled)
        assertTrue(UsageDatabaseConfig.parse(file("""{"url": "$url", "enabled": "on"}""")).enabled)
    }

    /** The file predates the section, and a project that records nothing never writes one. */
    @Test
    fun `a file with no section records nothing and is not an error`() {
        val without = """{"configurations": [{"name": "One", "url": "https://x/v1/messages"}]}"""

        assertEquals(UsageDatabaseConfig.OFF, UsageDatabaseConfig.parse(without))
        assertEquals(UsageDatabaseConfig.OFF, UsageDatabaseConfig.parse(""))
        // The bare-array form parseAll also accepts has nowhere to put a section.
        assertEquals(UsageDatabaseConfig.OFF, UsageDatabaseConfig.parse("""[{"name": "One"}]"""))
    }

    /** Reported rather than ignored: a field read as something else is a project recording nowhere. */
    @Test
    fun `refuses a section it cannot read`() {
        // The whole section written as the URL rather than as an object holding one.
        assertThrows(AgentConfigurationException::class.java) { UsageDatabaseConfig.parse(file("\"$url\"")) }
        assertThrows(AgentConfigurationException::class.java) {
            UsageDatabaseConfig.parse(file("""{"url": "$url", "enabled": "maybe"}"""))
        }
        assertThrows(AgentConfigurationException::class.java) { UsageDatabaseConfig.parse("model: gpt-5") }
    }

    /**
     * **Fill In Defaults** rewrites the whole file, so the section has to survive a round trip
     * through [AgentConfiguration.render] -- a URL that disappeared when an unrelated button was
     * pressed would take the recording with it and say nothing.
     */
    @Test
    fun `the section survives the file being rewritten`() {
        val database = UsageDatabaseConfig(url, enabled = false)

        val rendered = AgentConfiguration.render(AgentConfiguration.STARTER, database)

        assertEquals(database, UsageDatabaseConfig.parse(rendered))
        assertEquals(AgentConfiguration.STARTER, AgentConfiguration.parseAll(rendered))
    }
}
