package com.github.piotrszybicki.independentintelijaiplugin.aicodingagent

import com.intellij.openapi.diagnostic.Logger
import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.Encoding
import com.knuddels.jtokkit.api.EncodingType

/**
 * Counts what a piece of text costs in tokens, using JTokkit -- the JVM port of OpenAI's tiktoken.
 *
 * Here for the one decision that needs a real number rather than an estimate: whether a single tool
 * result is too large to send at all (see [AICodingAgent.run]'s `maxToolOutputTokens`). That limit
 * is a few hundred tokens, so the characters-over-four guess [HistoryCompaction] uses is not good
 * enough -- it is out by a factor of two on minified JSON, on base64, and on the dense punctuation
 * of a diff, which are exactly the outputs worth stopping.
 *
 * [HistoryCompaction] keeps its estimate. What it decides is fractions of a context window that is
 * itself a user-supplied round number, and running a tokenizer over the whole conversation before
 * every request would cost more than the decision is worth.
 *
 * ### Which encoding
 *
 * `o200k_base`, the one the current OpenAI models use. The endpoint here can be any provider and no
 * two of them tokenize alike -- Claude's tokenizer is not public at all -- so there is no counting
 * every result the way its own model will. What this needs to be is stable and roughly right, and
 * across providers the counts agree to within a few percent on ordinary text and code. A limit set
 * at 500 is not a limit anyone means to within five tokens.
 */
object TokenCounter {

    private val log = Logger.getInstance(TokenCounter::class.java)

    /** What the fallback estimates with when the encoding could not be loaded. */
    private const val CHARS_PER_TOKEN = 4

    /**
     * Loaded on first use rather than at startup, and through the *lazy* registry, which reads an
     * encoding's data the first time that encoding is asked for instead of all four at once. Both
     * halves matter: this is several megabytes of merge table that a conversation making no tool
     * calls never needs, and the first call for it lands on the agent's pooled thread rather than
     * anywhere near the EDT.
     *
     * Null when it could not be loaded -- a classloader that cannot see the resources in the jar is
     * the realistic way that happens. Worth degrading rather than failing: the count is a guard on
     * tool output, and a plugin that stops answering because its tokenizer is missing is a worse
     * outcome than one that goes back to estimating.
     */
    private val encoding: Encoding? by lazy {
        runCatching { Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.O200K_BASE) }
            .onFailure { log.warn("Could not load the o200k_base encoding; falling back to estimating tokens", it) }
            .getOrNull()
    }

    /** Whether real tokenization is available, as opposed to the fallback estimate. */
    val isExact: Boolean get() = encoding != null

    /**
     * How many tokens [text] is.
     *
     * `countTokensOrdinary` rather than `countTokens`: the latter refuses text containing a special
     * token -- `<|endoftext|>` and its relatives -- by throwing, and tool output is arbitrary file
     * contents. A file that happens to mention one of those strings must count as text and not take
     * the turn down with it, which is what "ordinary" means here.
     */
    fun count(text: String): Int {
        if (text.isEmpty()) return 0
        val encoding = encoding ?: return text.length / CHARS_PER_TOKEN
        return runCatching { encoding.countTokensOrdinary(text) }
            .getOrElse {
                log.warn("Counting tokens failed; estimating instead", it)
                text.length / CHARS_PER_TOKEN
            }
    }
}
