package com.github.piotrszybicki.independentintelijaiplugin.aicodingagent

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryCompactionTest {

    companion object {
        private const val RESULT_CHARS = 20_000

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
        val overhead = HistoryCompaction.charsOf(historyOf(turns = 10))

        // The same history is left alone on its own and compacted once the fixed part of the
        // request is counted too -- which is the whole point of measuring it. Thirty tool schemas
        // and a system prompt are not a rounding error next to a young conversation.
        assertTrue(HistoryCompaction.compact(history, WINDOW).isEmpty)
        assertEquals(untouched, history)
        assertFalse(HistoryCompaction.compact(history, WINDOW, overheadChars = overhead).isEmpty)
    }

    @Test
    fun `estimates the tool schemas as well as the prompt`() {
        val schema = JsonObject().apply { addProperty("type", "object") }
        val tools = listOf(ToolDefinition("read_project_file", "d".repeat(400), schema))

        val withTools = HistoryCompaction.overheadTokens("s".repeat(400), tools)

        assertTrue(withTools > HistoryCompaction.overheadTokens("s".repeat(400), emptyList()))
    }

    // --- tier 2: summarising ----------------------------------------------------------------------

    @Test
    fun `does not summarise while eliding tool output is still enough`() {
        val history = historyOf(turns = 20)
        var asked = false

        val result = HistoryCompaction.compact(history, WINDOW) { asked = true; "a summary" }

        // The expensive tier stays unused as long as the cheap one can do the job: 20 turns of tool
        // output is exactly the case eliding was written for.
        assertFalse("summarised when eliding would have done", asked)
        assertFalse(result.summarized)
        assertTrue(result.evicted > 0)
    }

    @Test
    fun `summarises when the conversation itself is what fills the window`() {
        // No tool calls at all, so there is nothing for tier 1 to elide and the window is full of
        // what the two of them wrote -- the case that used to have no answer.
        val history = proseHistoryOf(turns = 12)

        val result = HistoryCompaction.compact(history, WINDOW) { "the story so far" }

        assertTrue(result.summarized)
        assertEquals(0, result.evicted)
        assertTrue("nothing was freed", result.freedTokens > 0)
        assertTrue(textOf(history[0]).contains("the story so far"))
    }

    @Test
    fun `keeps the summary out of the protected tail`() {
        val history = proseHistoryOf(turns = 12)
        val tailStart = history.size - HistoryCompaction.PROTECTED_TAIL_MESSAGES
        val tail = history.subList(tailStart, history.size).toList()

        HistoryCompaction.compact(history, WINDOW) { "the story so far" }

        assertEquals(tail, history.takeLast(HistoryCompaction.PROTECTED_TAIL_MESSAGES))
    }

    @Test
    fun `leaves the history untouched when the summary cannot be had`() {
        val history = proseHistoryOf(turns = 12)
        val before = history.toList()

        val result = HistoryCompaction.compact(history, WINDOW) { null }

        // A failed summary request must cost nothing but the request: the alternative is a
        // conversation cut down on the strength of a summary that never arrived.
        assertFalse(result.summarized)
        assertEquals(before, history)
    }

    @Test
    fun `leaves the history untouched when the summary comes back blank`() {
        val history = proseHistoryOf(turns = 12)
        val before = history.toList()

        HistoryCompaction.compact(history, WINDOW) { "   \n  " }

        assertEquals(before, history)
    }

    @Test
    fun `cuts only at a turn boundary, so no tool result loses its tool use`() {
        // Prose turns with a tool call inside each one: every cut but the turn boundaries would
        // strand a tool_result whose tool_use it had just deleted, which the API refuses outright.
        val history = mutableListOf(ChatMessage.text("user", "go"))
        for (i in 0 until 12) {
            history.add(ChatMessage("assistant", toolUse("toolu_$i")))
            history.add(ChatMessage("user", toolResult("toolu_$i", "x".repeat(RESULT_CHARS))))
            history.add(ChatMessage.text("assistant", "p".repeat(RESULT_CHARS)))
            history.add(ChatMessage.text("user", "and now this: " + "q".repeat(RESULT_CHARS)))
        }

        HistoryCompaction.compact(history, WINDOW) { "the story so far" }

        for (i in history.indices) {
            val asked = idsOf(history[i], "tool_use", "id")
            if (asked.isEmpty()) continue
            assertEquals("message $i", asked, idsOf(history.getOrNull(i + 1), "tool_result", "tool_use_id"))
        }
        // And nothing is left answering a call that is no longer there.
        for (i in history.indices) {
            val answered = idsOf(history[i], "tool_result", "tool_use_id")
            if (answered.isEmpty()) continue
            assertEquals("message $i", answered, idsOf(history.getOrNull(i - 1), "tool_use", "id"))
        }
    }

    @Test
    fun `keeps the roles alternating across the splice`() {
        val history = proseHistoryOf(turns = 12)

        HistoryCompaction.compact(history, WINDOW) { "the story so far" }

        // The summary goes in as a user/assistant pair for this reason: a lone user message would
        // leave two user turns in a row where the kept half begins.
        assertEquals("user", history[0].role)
        assertEquals("assistant", history[1].role)
        for (i in 1 until history.size) {
            assertFalse("messages $i and ${i - 1} share a role", history[i].role == history[i - 1].role)
        }
    }

    @Test
    fun `declines to summarise a history too short to be worth it`() {
        // Over the trigger on the strength of two enormous messages. There is no cut that leaves the
        // tail protected and still takes enough with it, so a pass does nothing rather than
        // summarising a single exchange.
        val history = mutableListOf(
            ChatMessage.text("user", "x".repeat(RESULT_CHARS * 10)),
            ChatMessage.text("assistant", "y".repeat(RESULT_CHARS * 10)),
        )
        val before = history.toList()

        val result = HistoryCompaction.compact(history, WINDOW) { "the story so far" }

        assertTrue(result.isEmpty)
        assertEquals(before, history)
    }

    @Test
    fun `tells the model the summary is a summary`() {
        val history = proseHistoryOf(turns = 12)

        HistoryCompaction.compact(history, WINDOW) { "the story so far" }

        // Not dressed up as something the user wrote: a model that thinks it still has the
        // transcript will act on a paraphrase of it instead of re-reading.
        val inserted = textOf(history[0]).lowercase()
        assertTrue(inserted, inserted.contains("summary"))
        assertTrue(inserted, inserted.contains("not a transcript"))
    }

    @Test
    fun `asks for the things an agent mid-task needs kept`() {
        val request = HistoryCompaction.SUMMARY_REQUEST.lowercase()

        // A general "summarise this" drops exactly these first, and they are what the next turn is
        // built on.
        assertTrue(request.contains("outstanding"))
        assertTrue(request.contains("paths exactly"))
        assertTrue(request.contains("do not reproduce file contents"))
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private fun historyOf(turns: Int): MutableList<ChatMessage> {
        val history = mutableListOf(ChatMessage.text("user", "go"))
        for (i in 0 until turns) {
            history.add(ChatMessage("assistant", toolUse("toolu_$i")))
            history.add(ChatMessage("user", toolResult("toolu_$i", "x".repeat(RESULT_CHARS))))
        }
        return history
    }
    private fun proseHistoryOf(turns: Int): MutableList<ChatMessage> {
        val history = mutableListOf<ChatMessage>()
        for (i in 0 until turns) {
            history.add(ChatMessage.text("user", "ask $i: " + "q".repeat(RESULT_CHARS)))
            history.add(ChatMessage.text("assistant", "answer $i: " + "a".repeat(RESULT_CHARS)))
        }
        return history
    }

    private fun textOf(message: ChatMessage): String = message.content[0].asJsonObject.get("text").asString


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
