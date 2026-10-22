package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.github.piotrszybicki.independentintelijaiplugin.history.StoredRow
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class RestoreCheckpointTest {
    @Test
    fun latestEligibleCheckpointIsSelected() {
        val rows =
            listOf(
                StoredRow(StoredRow.USER, checkpointHistorySize = 0),
                StoredRow(StoredRow.USER, checkpointHistorySize = 2),
                StoredRow(StoredRow.USER, checkpointHistorySize = 5),
            )
        val selected =
            rows
                .asReversed()
                .firstOrNull {
                    it.kind == StoredRow.USER && it.checkpointHistorySize != null
                }?.checkpointHistorySize
        assertThat(selected).isEqualTo(5)
    }

    @Test
    fun noEligibleCheckpointProducesNoSelection() {
        val rows = listOf(StoredRow(StoredRow.ASSISTANT), StoredRow(StoredRow.TOOL), StoredRow(StoredRow.USER))
        val selected =
            rows
                .asReversed()
                .firstOrNull {
                    it.kind == StoredRow.USER && it.checkpointHistorySize != null
                }?.checkpointHistorySize
        assertThat(selected).isNull()
    }

    @Test
    fun promptDraftIsPreferredOverRenderedText() {
        val row =
            StoredRow(
                kind = StoredRow.USER,
                text = "Attached file instructions and original prompt",
                promptDraft = "Original prompt",
                checkpointHistorySize = 2,
            )
        assertThat(row.promptDraft ?: row.text).isEqualTo("Original prompt")
    }

    @Test
    fun rollbackRemovesSelectedRowAndFollowingRows() {
        val rows =
            mutableListOf(
                StoredRow(StoredRow.USER, checkpointHistorySize = 0),
                StoredRow(StoredRow.ASSISTANT),
                StoredRow(StoredRow.USER, checkpointHistorySize = 1),
                StoredRow(StoredRow.ASSISTANT),
            )
        val selectedIndex = rows.indexOfFirst { it.checkpointHistorySize == 1 }
        rows.subList(selectedIndex, rows.size).clear()
        assertThat(rows).hasSize(2)
        assertThat(rows.last().kind).isEqualTo(StoredRow.ASSISTANT)
    }
}
