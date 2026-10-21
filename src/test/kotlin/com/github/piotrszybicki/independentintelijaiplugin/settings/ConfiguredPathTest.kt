package com.github.piotrszybicki.independentintelijaiplugin.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Paths

class ConfiguredPathTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `nothing set resolves to nothing`() {
        assertNull(AgentConfiguration.configuredPath(null))
        assertNull(AgentConfiguration.configuredPath(""))
        assertNull(AgentConfiguration.configuredPath("   "))
    }

    @Test
    fun `a missing environment variable is a configuration problem`() {
        assertEquals(
            "${AgentConfiguration.PATH_ENV_VAR} is not set.",
            AgentConfiguration.environmentProblem(null),
        )
    }

    @Test
    fun `a usable environment path has no environment problem`() {
        assertNull(AgentConfiguration.environmentProblem(folder.newFile("settings.json").toString()))
    }

    @Test
    fun `a file path is used as it is`() {
        val file = folder.newFile("providers.json").toPath()

        assertEquals(file, AgentConfiguration.configuredPath(file.toString()))
    }

    @Test
    fun `a file path is trimmed`() {
        val file = folder.newFile("trimmed.json").toPath()

        assertEquals(file, AgentConfiguration.configuredPath("  $file  "))
    }

    @Test
    fun `a directory is not a configuration file`() {
        val directory = folder.newFolder("settings").toPath()

        assertNull(AgentConfiguration.configuredPath(directory.toString()))
    }

    @Test
    fun `a path that does not exist still resolves`() {
        val missing =
            folder.root
                .toPath()
                .resolve("not-there")
                .resolve("providers.json")

        assertEquals(missing, AgentConfiguration.configuredPath(missing.toString()))
    }

    @Test
    fun `a relative path is not accepted`() {
        assertNull(AgentConfiguration.configuredPath("providers.json"))
    }

    @Test
    fun `a tilde is the home directory`() {
        val home = Paths.get(System.getProperty("user.home"))

        assertEquals(
            home.resolve("ai").resolve("providers.json"),
            AgentConfiguration.configuredPath("~/ai/providers.json"),
        )
    }

    @Test
    fun `a bare tilde is not a configuration file`() {
        assertNull(AgentConfiguration.configuredPath("~"))
    }
}
