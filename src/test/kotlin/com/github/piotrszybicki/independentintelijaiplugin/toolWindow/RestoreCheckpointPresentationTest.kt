package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class RestoreCheckpointPresentationTest {
    @Test
    fun checkpointControlIsSeparateFromTheUserPrompt() {
        val rows = listOf<ChatRow>(RestoreCheckpointRow {}, UserRow("Prompt"))

        assertThat(rows.map { it::class }).containsExactly(
            RestoreCheckpointRow::class,
            UserRow::class,
        )
        assertThat(rows[0].name).isEqualTo("chat.restoreCheckpoint")
        assertThat(rows[1].name).isNull()
    }

    @Test
    fun nonCheckpointPromptHasNoRestoreSeparator() {
        val rows = listOf<ChatRow>(UserRow("Prompt"))

        assertThat(rows.map { it::class }).containsExactly(UserRow::class)
        assertThat(rows[0].name).isNull()
    }
}
