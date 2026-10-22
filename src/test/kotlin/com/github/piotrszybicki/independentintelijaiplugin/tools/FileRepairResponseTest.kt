package com.github.piotrszybicki.independentintelijaiplugin.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FileRepairResponseTest {
    @Test
    fun `accepts entire file with whitespace and escaped characters intact`() {
        assertEquals(
            "fun main() {\n    println(\"ok\")\n}\n",
            FileRepairConversation.replacement(
                """{"content":"fun main() {\n    println(\"ok\")\n}\n"}""",
            ),
        )
    }

    @Test
    fun `rejects malformed truncated and non file responses`() {
        for (response in listOf(
            "{\"content\":",
            "[]",
            "{}",
            "{\"content\":1}",
            "{\"content\":null}",
            "{\"content\":\"file\",\"extra\":true}",
            "```json\n{\"content\":\"file\"}\n```",
        )) {
            assertThrows(RuntimeException::class.java) { FileRepairConversation.replacement(response) }
        }
    }
}
