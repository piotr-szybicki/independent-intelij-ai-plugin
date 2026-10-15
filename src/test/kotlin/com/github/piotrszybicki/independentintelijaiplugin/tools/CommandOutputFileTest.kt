package com.github.piotrszybicki.independentintelijaiplugin.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CommandOutputFileTest {
    @Test
    fun `persists output and reads the digest from disk`() {
        val path = Files.createTempDirectory("command-output-test").resolve("command.log")
        val output = CommandOutputFile.at(path)

        output.append("first line\n")
        output.append("second line\n")

        assertEquals("first line\nsecond line", output.digest(null))
        assertEquals("first line\nsecond line\n", Files.readString(path))
        output.close()
    }

    @Test
    fun `can read a live flushed log`() {
        val path = Files.createTempDirectory("command-output-live-test").resolve("command.log")
        val output = CommandOutputFile.at(path)

        output.append("available while running\n")

        assertTrue(Files.readString(path).contains("available while running"))
        output.close()
    }
}
