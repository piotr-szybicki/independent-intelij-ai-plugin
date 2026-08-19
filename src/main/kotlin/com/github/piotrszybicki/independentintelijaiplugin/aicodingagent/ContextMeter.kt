package com.github.piotrszybicki.independentintelijaiplugin.aicodingagent

class ContextMeter {

    companion object {
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

    var tokensPerChar: Double = DEFAULT_TOKENS_PER_CHAR
        private set

    val anchor: Int get() = anchorTokens

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

    fun invalidateAnchor() {
        anchorTokens = 0
        anchorMessages = 0
    }

    fun reset() {
        invalidateAnchor()
        tokensPerChar = DEFAULT_TOKENS_PER_CHAR
    }

    data class State(
        val anchorTokens: Int = 0,
        val anchorMessages: Int = 0,
        val tokensPerChar: Double = DEFAULT_TOKENS_PER_CHAR,
    )

    fun snapshot(): State = State(anchorTokens, anchorMessages, tokensPerChar)

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
