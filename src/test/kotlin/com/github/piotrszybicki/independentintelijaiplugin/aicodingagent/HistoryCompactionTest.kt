package com.github.piotrszybicki.independentintelijaiplugin.aicodingagent

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers when compaction fires and what it leaves behind.
 *
 * Two of these guard things nothing local would otherwise catch. Eliding a `tool_result` into
 * something the next request cannot pair with its `tool_use` is a conversation the API refuses from
 * then on -- permanently, since the damage is written to the saved chat. And firing on a history
 * that is nowhere near the window would quietly throw away the model's working set on every turn,
 * which does not fail, it just makes the assistant worse.
 */
class HistoryCompactionTest {

    companion object {
        /** One tool result. Comfortably past the minimum below which a block is left alone. */
        private const val RESULT_CHARS = 20_000

        /**
         * Small enough that these fixtures cross the trigger without needing megabytes of them, and
         * large enough that the protected tail still fits under the target -- otherwise a pass
         * could not reach its target however much it dropped, and `stops once the estimate is back
         * under target` would be asserting something impossible rather than something true.
         */
        private const val WINDOW = 100_000
    }

    @Test
    fun `leaves a conversation that fits well inside the window alone`() {
        val history = historyOf(turns = 3)
        val before = history.toList()

        val result = HistoryCompaction.compact(history, contextWindowTokens = 10_000_000)

        assertTrue(result.isEmpty)
        assertEquals(before, history)
    }

    @Test
    fun `is off entirely when no context window is configured`() {
        val history = historyOf(turns = 40)
        val before = history.toList()

        val result = HistoryCompaction.compact(history, contextWindowTokens = 0)

        assertTrue(result.isEmpty)
        assertEquals(before, history)
    }

    @Test
    fun `elides old tool output once the conversation outgrows the window`() {
        val history = historyOf(turns = 20)

        val result = HistoryCompaction.compact(history, WINDOW)

        assertFalse(result.isEmpty)
        assertTrue("nothing was freed", result.freedTokens > 0)
        assertTrue("the oldest result should be gone", oldestResult(history).startsWith("[older tool output removed"))
    }

    @Test
    fun `stops once the estimate is back under target`() {
        val history = historyOf(turns = 20)

        val result = HistoryCompaction.compact(history, WINDOW)

        assertTrue("still over target: ${result.afterTokens}", result.afterTokens <= WINDOW * 0.4)
        // And stopped there rather than emptying everything it was allowed to touch: 20 turns are
        // 16 elidable results, and getting under target does not take all of them.
        assertTrue("evicted everything in range", result.evicted < 16)
    }

    @Test
    fun `never touches the most recent messages`() {
        val history = historyOf(turns = 40)
        val tailStart = history.size - HistoryCompaction.PROTECTED_TAIL_MESSAGES
        val tail = history.subList(tailStart, history.size).toList()

        HistoryCompaction.compact(history, WINDOW)

        assertEquals(tail, history.subList(tailStart, history.size))
    }

    @Test
    fun `keeps every tool result paired with its tool use`() {
        val history = historyOf(turns = 20)

        HistoryCompaction.compact(history, WINDOW)

        // The invariant the API enforces: every id asked for in an assistant turn is answered in
        // the message right after it. Eliding may change what a result says, never which call it
        // belongs to.
        for (i in history.indices) {
            val asked = idsOf(history[i], "tool_use", "id")
            if (asked.isEmpty()) continue
            assertEquals("message $i", asked, idsOf(history.getOrNull(i + 1), "tool_result", "tool_use_id"))
        }
    }

    @Test
    fun `names the tool whose output it dropped and sizes it in the root locale`() {
        val history = historyOf(turns = 20)

        HistoryCompaction.compact(history, WINDOW)

        assertTrue(oldestResult(history).contains("read_project_file"))
        // A decimal point, not a comma: this formats through Locale.ROOT, or it reads as "19,5 kB"
        // on every machine whose locale separates decimals the other way.
        assertTrue(oldestResult(history), oldestResult(history).contains("19.5 kB"))
    }

    @Test
    fun `says nothing that invites re-running a tool that writes`() {
        val history = historyOf(turns = 20)

        HistoryCompaction.compact(history, WINDOW)

        // Half the catalog edits files or runs commands, and the stub cannot tell which -- so it
        // must never suggest the call be made again. Re-running an edit whose line numbers have
        // since moved is worse than the context it would save.
        val stub = oldestResult(history).lowercase()
        assertFalse(stub, stub.contains("call it again"))
        assertFalse(stub, stub.contains("if you need"))
        assertFalse(stub, stub.contains("re-run"))
    }

    @Test
    fun `a second pass over an already compacted history changes nothing`() {
        val history = historyOf(turns = 20)
        HistoryCompaction.compact(history, WINDOW)
        val compacted = history.toList()

        val again = HistoryCompaction.compact(history, WINDOW)

        // The point of eliding the history itself rather than the outgoing request: what was
        // dropped stays dropped, so the prefix the cache matches on stops moving.
        assertTrue(again.isEmpty)
        assertEquals(compacted, history)
    }

    @Test
    fun `leaves short results alone`() {
        val history = historyOf(turns = 20)
        // The answer to the second tool call, well inside the range the pass walks.
        history[4] = ChatMessage("user", toolResult("toolu_1", "ok"))

        HistoryCompaction.compact(history, WINDOW)

        assertTrue(oldestResult(history).startsWith("[older tool output removed"))
        assertEquals("ok", contentOf(history[4]))
    }

    @Test
    fun `counts the system prompt and tool schemas against the window`() {
        val history = historyOf(turns = 10)
        val untouched = history.toList()
        val overhead = HistoryCompaction.estimateTokens(historyOf(turns = 10))

        // The same history is left alone on its own and compacted once the fixed part of the
        // request is counted too -- which is the whole point of measuring it. Thirty tool schemas
        // and a system prompt are not a rounding error next to a young conversation.
        assertTrue(HistoryCompaction.compact(history, WINDOW).isEmpty)
        assertEquals(untouched, history)
        assertFalse(HistoryCompaction.compact(history, WINDOW, overheadTokens = overhead).isEmpty)
    }

    @Test
    fun `estimates the tool schemas as well as the prompt`() {
        val schema = JsonObject().apply { addProperty("type", "object") }
        val tools = listOf(ToolDefinition("read_project_file", "d".repeat(400), schema))

        val withTools = HistoryCompaction.overheadTokens("s".repeat(400), tools)

        assertTrue(withTools > HistoryCompaction.overheadTokens("s".repeat(400), emptyList()))
    }

    // --- fixtures ---------------------------------------------------------------------------------

    /** [turns] rounds of "the assistant asks for a tool, the user answers it". */
    private fun historyOf(turns: Int): MutableList<ChatMessage> {
        val history = mutableListOf(ChatMessage.text("user", "go"))
        for (i in 0 until turns) {
            history.add(ChatMessage("assistant", toolUse("toolu_$i")))
            history.add(ChatMessage("user", toolResult("toolu_$i", "x".repeat(RESULT_CHARS))))
        }
        return history
    }

    private fun toolUse(id: String): JsonArray = JsonArray().apply {
        add(JsonObject().apply {
            addProperty("type", "tool_use")
            addProperty("id", id)
            addProperty("name", "read_project_file")
        })
    }

    private fun toolResult(id: String, body: String): JsonArray = JsonArray().apply {
        add(JsonObject().apply {
            addProperty("type", "tool_result")
            addProperty("tool_use_id", id)
            addProperty("content", body)
        })
    }

    /** The text of the first `tool_result` in [history] -- the one a pass reaches first. */
    private fun oldestResult(history: List<ChatMessage>): String = contentOf(
        history.first { message ->
            message.content.any { it.asJsonObject.get("type")?.asString == "tool_result" }
        },
    )

    private fun contentOf(message: ChatMessage): String = message.content[0].asJsonObject.get("content").asString

    private fun idsOf(message: ChatMessage?, type: String, idField: String): Set<String> {
        if (message == null) return emptySet()
        val ids = linkedSetOf<String>()
        for (block in message.content) {
            val obj = block.asJsonObject
            if (obj.get("type")?.asString != type) continue
            obj.get(idField)?.asString?.let { ids.add(it) }
        }
        return ids
    }
}
