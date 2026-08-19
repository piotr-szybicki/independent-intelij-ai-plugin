package com.github.piotrszybicki.independentintelijaiplugin.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

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

    @Test
    fun `an empty url records nothing however the switch is set`() {
        assertFalse(UsageDatabaseConfig.parse(file("""{"url": "", "enabled": true}""")).isActive)
        assertFalse(UsageDatabaseConfig.OFF.isActive)
    }

    @Test
    fun `the switch is read from a quoted boolean too`() {
        assertFalse(UsageDatabaseConfig.parse(file("""{"url": "$url", "enabled": "false"}""")).enabled)
        assertTrue(UsageDatabaseConfig.parse(file("""{"url": "$url", "enabled": "on"}""")).enabled)
    }

    @Test
    fun `a file with no section records nothing and is not an error`() {
        val without = """{"configurations": [{"name": "One", "url": "https://x/v1/messages"}]}"""

        assertEquals(UsageDatabaseConfig.OFF, UsageDatabaseConfig.parse(without))
        assertEquals(UsageDatabaseConfig.OFF, UsageDatabaseConfig.parse(""))
        assertEquals(UsageDatabaseConfig.OFF, UsageDatabaseConfig.parse("""[{"name": "One"}]"""))
    }

    @Test
    fun `refuses a section it cannot read`() {
        assertThrows(AgentConfigurationException::class.java) { UsageDatabaseConfig.parse(file("\"$url\"")) }
        assertThrows(AgentConfigurationException::class.java) {
            UsageDatabaseConfig.parse(file("""{"url": "$url", "enabled": "maybe"}"""))
        }
        assertThrows(AgentConfigurationException::class.java) { UsageDatabaseConfig.parse("model: gpt-5") }
    }

    @Test
    fun `the section survives the file being rewritten`() {
        val database = UsageDatabaseConfig(url, enabled = false)

        val rendered = AgentConfiguration.render(AgentConfiguration.STARTER, database)

        assertEquals(database, UsageDatabaseConfig.parse(rendered))
        assertEquals(AgentConfiguration.STARTER, AgentConfiguration.parseAll(rendered))
    }
}
