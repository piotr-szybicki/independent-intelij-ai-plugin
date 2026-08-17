package com.github.piotrszybicki.independentintelijaiplugin.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Paths

/**
 * What `INTELIJ_AI_SETTINGS` turns into.
 *
 * Pinned down away from the environment because this is the one decision in the plugin that has no
 * fallback worth having: get it wrong and either the file being read is not the file being edited, or
 * the plugin refuses to start over a path that was fine. The variable itself is read in one line --
 * see [AgentConfigurations.path].
 */
class ConfiguredPathTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** Unset, empty and whitespace all mean "the project root decides", which is the default. */
    @Test
    fun `nothing set resolves to nothing`() {
        assertNull(AgentConfiguration.configuredPath(null))
        assertNull(AgentConfiguration.configuredPath(""))
        assertNull(AgentConfiguration.configuredPath("   "))
    }

    @Test
    fun `a file path is used as it is`() {
        val file = folder.newFile("providers.json").toPath()

        assertEquals(file, AgentConfiguration.configuredPath(file.toString()))
    }

    /** Surrounding whitespace comes free with a variable set in a shell profile. */
    @Test
    fun `a file path is trimmed`() {
        val file = folder.newFile("trimmed.json").toPath()

        assertEquals(file, AgentConfiguration.configuredPath("  $file  "))
    }

    /** A folder is as likely to be typed as a filename, and can only mean the file inside it. */
    @Test
    fun `a directory resolves to the file inside it`() {
        val directory = folder.newFolder("settings").toPath()

        assertEquals(
            directory.resolve(AgentConfiguration.FILE_NAME),
            AgentConfiguration.configuredPath(directory.toString()),
        )
    }

    /**
     * Missing is not the same as unusable: a path that is not there still resolves, because it is
     * [AgentConfigurations.unavailableReason] that decides what a missing file means and it needs the
     * path to say it.
     */
    @Test
    fun `a path that does not exist still resolves`() {
        val missing = folder.root.toPath().resolve("not-there").resolve("providers.json")

        assertEquals(missing, AgentConfiguration.configuredPath(missing.toString()))
    }

    /** Absolute, so what it names cannot depend on the directory the IDE happened to start in. */
    @Test
    fun `a relative path is made absolute`() {
        val resolved = AgentConfiguration.configuredPath("providers.json")

        assertEquals(Paths.get("providers.json").toAbsolutePath().normalize(), resolved)
    }

    @Test
    fun `a tilde is the home directory`() {
        val home = Paths.get(System.getProperty("user.home"))

        assertEquals(
            home.resolve("ai").resolve("providers.json"),
            AgentConfiguration.configuredPath("~/ai/providers.json"),
        )
    }

    /** `~` on its own is a directory, so it means the file in the home directory. */
    @Test
    fun `a bare tilde is the file in the home directory`() {
        assertEquals(
            Paths.get(System.getProperty("user.home")).resolve(AgentConfiguration.FILE_NAME),
            AgentConfiguration.configuredPath("~"),
        )
    }
}
