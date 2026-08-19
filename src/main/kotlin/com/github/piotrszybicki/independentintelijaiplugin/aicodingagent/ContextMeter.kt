package com.github.piotrszybicki.independentintelijaiplugin.aicodingagent

class ContextMeter {

    companion object {
        const val DEFAULT_TOKENS_PER_CHAR: Double = 1.0 / 4

        private const val MIN_TOKENS_PER_CHAR = 1.0 / 20
        private const val MAX_TOKENS_PER_CHAR = 2.0
    }

    private var anchorTokens = 0

    private var anchorMessages = 0

    var tokensPerChar: Double = DEFAULT_TOKENS_PER_CHAR
        private set

    val anchor: Int get() = anchorTokens

    fun observe(promptTokens: Int, outputTokens: Int, promptChars: Int, coveredMessages: Int) {
        if (promptTokens <= 0) return

        anchorTokens = promptTokens + outputTokens.coerceAtLeast(0)
        anchorMessages = coveredMessages

        if (promptChars <= 0) return
        val ratio = promptTokens.toDouble() / promptChars
        if (ratio < MIN_TOKENS_PER_CHAR || ratio > MAX_TOKENS_PER_CHAR) return
        tokensPerChar = ratio
    }

    fun estimate(history: List<ChatMessage>, overheadChars: Int): Int {
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
        tokensPerChar = state.tokensPerChar
            .takeIf { it >= MIN_TOKENS_PER_CHAR && it <= MAX_TOKENS_PER_CHAR }
            ?: DEFAULT_TOKENS_PER_CHAR
    }

    private fun tokens(chars: Int): Int = (chars.coerceAtLeast(0) * tokensPerChar).toInt()
}
