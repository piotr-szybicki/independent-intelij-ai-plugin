package com.github.piotrszybicki.independentintelijaiplugin.aicodingagent

/**
 * How much of the context window a conversation is using.
 *
 * The number worth showing is not the session totals in [SessionUsage]: those sum across requests,
 * and since every request resends the whole history they grow without bound while the window stays
 * where it is. What fills the window is one request's prompt -- so that is what this measures.
 *
 * The provider already counts it exactly, in the usage block of every response: see
 * [AICodingAgentUsage.promptTokens]. That figure is an anchor rather than the answer, because it
 * describes the conversation as it was when the request went out, and a tool loop can add more to
 * the history in the seconds after than the user added all morning. So what is reported is the
 * anchor plus a local measure of whatever arrived since.
 *
 * One meter belongs to one conversation and outlives any single turn: the anchor and the
 * calibration are what it has learned about this conversation, and starting over on every turn
 * would throw both away.
 */
class ContextMeter {

    companion object {
        /** What a character is worth before any response has been seen. */
        const val DEFAULT_TOKENS_PER_CHAR: Double = 1.0 / 4

        // A ratio outside this range is not a conversation that tokenizes unusually, it is a count
        // that does not describe the prompt that was measured -- a provider reporting something
        // other than prompt tokens, or an anchor taken against the wrong history. Ignored rather
        // than believed: a wrong calibration is worse than a crude one, because it is wrong in the
        // same direction on every subsequent measurement.
        private const val MIN_TOKENS_PER_CHAR = 1.0 / 20
        private const val MAX_TOKENS_PER_CHAR = 2.0
    }

    // The last prompt the provider counted, plus the reply it returned: together, what the next
    // request starts from. Zero until a response has been seen.
    private var anchorTokens = 0

    // How many messages that figure covers. Anything past this index is measured here instead.
    private var anchorMessages = 0

    /**
     * Tokens per character for this conversation's text, learned from what the provider reported
     * against what was sent.
     *
     * Worth learning rather than assuming, because it silently carries everything counting
     * characters cannot see: the JSON framing around every message, tool schemas, images, the
     * per-message overhead the provider adds, and the fact that code does not tokenize like prose.
     */
    var tokensPerChar: Double = DEFAULT_TOKENS_PER_CHAR
        private set

    /** The last figure the provider reported, or zero before the first response. */
    val anchor: Int get() = anchorTokens

    /**
     * Take a response's usage as the new anchor.
     *
     * [promptChars] must be the history as it was *sent*, plus the system prompt and tool schemas;
     * [coveredMessages] the size of the history once the reply has been appended to it. The reply
     * counts because it is part of the next request, and the two arguments differ for exactly that
     * reason.
     */
    fun observe(promptTokens: Int, outputTokens: Int, promptChars: Int, coveredMessages: Int) {
        // A provider that reports no prompt tokens leaves the previous anchor in place: a turn
        // measured against a stale anchor is off by one turn, one measured against zero is off by
        // the whole conversation.
        if (promptTokens <= 0) return

        anchorTokens = promptTokens + outputTokens.coerceAtLeast(0)
        anchorMessages = coveredMessages

        if (promptChars <= 0) return
        val ratio = promptTokens.toDouble() / promptChars
        if (ratio < MIN_TOKENS_PER_CHAR || ratio > MAX_TOKENS_PER_CHAR) return
        tokensPerChar = ratio
    }

    /**
     * What the next request to this conversation would cost, in tokens.
     */
    fun estimate(history: List<ChatMessage>, overheadChars: Int): Int {
        // Before the first response, and after a compaction has invalidated the anchor, there is
        // nothing to anchor to and the whole request is measured here. Less accurate, and the only
        // thing available.
        if (anchorTokens <= 0 || anchorMessages > history.size) {
            return tokens(overheadChars + HistoryCompaction.charsOf(history))
        }
        val since = HistoryCompaction.charsOf(history.subList(anchorMessages, history.size))
        return anchorTokens + tokens(since)
    }

    /**
     * Give up the anchor, keeping what has been learned about the tokenizer.
     *
     * Compaction rewrites messages the anchor already counted, so the figure the provider gave for
     * them no longer describes what is there -- and it is always too high, which is the direction
     * that hides the room the compaction just made.
     */
    fun invalidateAnchor() {
        anchorTokens = 0
        anchorMessages = 0
    }

    /** Start over: a different conversation tells us nothing about this one. */
    fun reset() {
        invalidateAnchor()
        tokensPerChar = DEFAULT_TOKENS_PER_CHAR
    }

    /**
     * What a meter knows, in a form that can be written to disk with the conversation it describes.
     *
     * Saved rather than recomputed, because none of it can be worked out again from the messages:
     * the anchor is what a provider said about a request that has already been and gone, and the
     * ratio is what several of those taught us. A reopened chat with no anchor would have to guess
     * at its own size -- and guess low, since the system prompt and the tool schemas are not in the
     * history it was saved with.
     */
    data class State(
        val anchorTokens: Int = 0,
        val anchorMessages: Int = 0,
        val tokensPerChar: Double = DEFAULT_TOKENS_PER_CHAR,
    )

    fun snapshot(): State = State(anchorTokens, anchorMessages, tokensPerChar)

    /**
     * Take up where a saved conversation left off. A null state -- a chat written before any of
     * this was recorded -- resets instead, leaving the meter with nothing to show until the next
     * response gives it something.
     */
    fun restore(state: State?) {
        if (state == null) {
            reset()
            return
        }
        anchorTokens = state.anchorTokens.coerceAtLeast(0)
        anchorMessages = state.anchorMessages.coerceAtLeast(0)
        // Read back through the same guard the live figure goes through: the file is editable, and
        // a zero here would make every later estimate zero.
        tokensPerChar = state.tokensPerChar
            .takeIf { it >= MIN_TOKENS_PER_CHAR && it <= MAX_TOKENS_PER_CHAR }
            ?: DEFAULT_TOKENS_PER_CHAR
    }

    private fun tokens(chars: Int): Int = (chars.coerceAtLeast(0) * tokensPerChar).toInt()
}
