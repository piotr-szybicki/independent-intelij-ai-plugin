package com.github.piotrszybicki.detektrules

import dev.detekt.api.Config
import dev.detekt.test.lint
import kotlin.test.Test
import kotlin.test.assertEquals

class OneTopLevelClassOrObjectPerFileTest {
    private val rule = OneTopLevelClassOrObjectPerFile(Config.empty)

    @Test
    fun `reports every top-level type after the first`() {
        val findings =
            rule.lint(
                """
                class First
                interface Second
                object Third
                """.trimIndent(),
            )

        assertEquals(2, findings.size)
    }

    @Test
    fun `allows nested types and top-level functions`() {
        val findings =
            rule.lint(
                """
                class Outer {
                    class Nested
                }

                fun helper() = Unit
                """.trimIndent(),
            )

        assertEquals(0, findings.size)
    }
}
