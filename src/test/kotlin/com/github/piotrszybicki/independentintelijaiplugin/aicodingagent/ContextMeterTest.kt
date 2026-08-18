package com.github.piotrszybicki.independentintelijaiplugin.aicodingagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextMeterTest {

    companion object {
        private const val MESSAGE_CHARS = 1_000
    }

    @Test
    fun `estimates from characters before any response has been seen`() {
        val meter = ContextMeter()
        val history = historyOf(4)

        val estimate = meter.estimate(history, overheadChars = 0)

        assertEquals(
            (HistoryCompaction.charsOf(history) * ContextMeter.DEFAULT_TOKENS_PER_CHAR).toInt(),
            estimate,
        )
    }

    @Test
    fun `counts the fixed part of the request too`() {
        val meter = ContextMeter()
        val history = historyOf(2)

        assertTrue(
            meter.estimate(history, overheadChars = 40_000) >
                meter.estimate(history, overheadChars = 0),
        )
    }

    @Test
    fun `reports what the provider counted, plus the reply it returned`() {
        val meter = ContextMeter()
        val history = historyOf(3)
        meter.observe(promptTokens = 50_000, outputTokens = 700, promptChars = 200_000, coveredMessages = history.size)

        // Nothing has been added since, so the anchor is the whole answer -- and it is the
        // provider's figure rather than anything measured here.
        assertEquals(50_700, meter.estimate(history, overheadChars = 0))
    }

    @Test
    fun `adds what has arrived since the anchor, at the ratio it learned`() {
        val meter = ContextMeter()
        val history = historyOf(3).toMutableList()
        meter.observe(promptTokens = 50_000, outputTokens = 0, promptChars = 100_000, coveredMessages = history.size)

        history += message("user", "x".repeat(MESSAGE_CHARS))
        val added = meter.estimate(history, overheadChars = 0) - 50_000

        // Half a token per character, learned from the response just seen, rather than the opening
        // guess of a quarter. A little over half the text, because what is measured is the JSON the
        // message is carried in and not the text alone.
        assertTrue("expected about half of $MESSAGE_CHARS characters, got $added", added >= MESSAGE_CHARS / 2)
        assertTrue("expected about half of $MESSAGE_CHARS characters, got $added", added < MESSAGE_CHARS)
    }

    @Test
    fun `learns what a character is worth from what the provider reported`() {
        val meter = ContextMeter()

        meter.observe(promptTokens = 40_000, outputTokens = 0, promptChars = 100_000, coveredMessages = 1)

        assertEquals(0.4, meter.tokensPerChar, 0.0001)
    }

    @Test
    fun `ignores a ratio that cannot describe the prompt it was measured against`() {
        val meter = ContextMeter()

        // Ten tokens per character: whatever this response is reporting, it is not the size of
        // what was sent.
        meter.observe(promptTokens = 1_000_000, outputTokens = 0, promptChars = 100_000, coveredMessages = 1)

        assertEquals(ContextMeter.DEFAULT_TOKENS_PER_CHAR, meter.tokensPerChar, 0.0001)
    }

    @Test
    fun `keeps the previous anchor when a response reports no prompt tokens`() {
        val meter = ContextMeter()
        val history = historyOf(3)
        meter.observe(promptTokens = 50_000, outputTokens = 0, promptChars = 200_000, coveredMessages = history.size)

        meter.observe(promptTokens = 0, outputTokens = 0, promptChars = 200_000, coveredMessages = history.size)

        assertEquals(50_000, meter.estimate(history, overheadChars = 0))
    }

    @Test
    fun `falls back to measuring when compaction has invalidated the anchor`() {
        val meter = ContextMeter()
        val history = historyOf(6)
        meter.observe(promptTokens = 50_000, outputTokens = 0, promptChars = 200_000, coveredMessages = history.size)

        meter.invalidateAnchor()

        // Measured rather than anchored, and so far smaller than the figure that described a
        // history this one no longer is.
        assertTrue(meter.estimate(history, overheadChars = 0) < 50_000)
        // What was learned about the tokenizer survives: the messages changed, the language did not.
        assertEquals(0.25, meter.tokensPerChar, 0.0001)
    }

    @Test
    fun `stops trusting an anchor that covers more messages than the history has`() {
        val meter = ContextMeter()
        val history = historyOf(6)
        meter.observe(promptTokens = 50_000, outputTokens = 0, promptChars = 200_000, coveredMessages = 99)

        assertTrue(meter.estimate(history, overheadChars = 0) < 50_000)
    }

    @Test
    fun `forgets everything when the conversation is replaced`() {
        val meter = ContextMeter()
        meter.observe(promptTokens = 40_000, outputTokens = 0, promptChars = 100_000, coveredMessages = 1)

        meter.reset()

        assertEquals(0, meter.anchor)
        assertEquals(ContextMeter.DEFAULT_TOKENS_PER_CHAR, meter.tokensPerChar, 0.0001)
    }

    @Test
    fun `comes back from a saved state with the figure it was saved with`() {
        val history = historyOf(4)
        val saved = ContextMeter()
        saved.observe(promptTokens = 50_000, outputTokens = 700, promptChars = 100_000, coveredMessages = history.size)

        val reopened = ContextMeter()
        reopened.restore(saved.snapshot())

        // Including the system prompt and the tool schemas, which the history it was restored
        // alongside does not contain: they were part of what the provider counted.
        assertEquals(50_700, reopened.estimate(history, overheadChars = 0))
        assertEquals(0.5, reopened.tokensPerChar, 0.0001)
    }

    @Test
    fun `shows nothing for a chat saved before the meter existed`() {
        val meter = ContextMeter()
        meter.observe(promptTokens = 50_000, outputTokens = 0, promptChars = 100_000, coveredMessages = 4)

        meter.restore(null)

        assertEquals(0, meter.anchor)
        assertEquals(ContextMeter.DEFAULT_TOKENS_PER_CHAR, meter.tokensPerChar, 0.0001)
    }

    @Test
    fun `refuses a saved ratio that would make every later estimate nonsense`() {
        val meter = ContextMeter()

        meter.restore(ContextMeter.State(anchorTokens = 10, anchorMessages = 1, tokensPerChar = 0.0))

        assertEquals(ContextMeter.DEFAULT_TOKENS_PER_CHAR, meter.tokensPerChar, 0.0001)
    }

    private fun historyOf(messages: Int): List<ChatMessage> =
        (0 until messages).map { message(if (it % 2 == 0) "user" else "assistant", "m".repeat(MESSAGE_CHARS)) }

    private fun message(role: String, text: String): ChatMessage = ChatMessage.text(role, text)
}
