package com.github.piotrszybicki.independentintelijaiplugin.logging

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * What a request cost, worked out from its token counts and a published price per model.
 *
 * In the plugin rather than in a table on the server, because the chat window shows this under every
 * reply and the database is optional: usage logging is off by default and the URL may be empty, so
 * rates that lived in SQL would leave the figure the user actually reads with nothing behind it. One
 * source, used twice -- the label under a turn and the `cost_usd` column are the same function on the
 * same counts, so they cannot disagree.
 *
 * The table below is a copy of the providers' list prices, and copies go stale: a price that changes
 * on their side changes nothing here until someone edits this file. That is the honest limit of both
 * -- an estimate at the rates written here, not a bill, and it will not match an invoice that
 * includes batch discounts, negotiated rates, a long-context surcharge or anything else priced
 * outside the four counts a usage block carries.
 *
 * It is still worth showing. The question actually asked is "which of these replies is expensive",
 * and token counts cannot answer it across models: a hundred thousand cache reads on Haiku and ten
 * thousand output tokens on Opus are not comparable as tokens and are immediately comparable as
 * dollars. Being wrong by a rate change is a much smaller error than comparing the wrong quantities.
 *
 * ### An unpriced model has no cost
 *
 * [costUsd] returns null rather than zero for a model that is not in the table -- a local Ollama
 * model, or one released after this file was last edited. Zero would be a claim that the request was
 * free, which for the second of those is wrong in the direction that hides the problem. Nothing is
 * drawn under the reply, and the column stays NULL, which `AVG` skips and `WHERE cost_usd IS NULL`
 * finds.
 */
object ModelPricing {

    /** USD per 1M tokens, one field per column of the provider's price list. */
    data class Rates(
        val input: Double,
        val cacheRead: Double,
        val cacheWrite: Double,
        val output: Double,
    )

    /**
     * Prices per 1M tokens, keyed by the part of a model id that identifies the model.
     *
     * Keys are matched as substrings of the id that was actually sent (see [ratesFor]), so
     * `claude-haiku-4-5` covers `claude-haiku-4-5-20251001` and every later dated build of it
     * without a line each. Dropping the date from the key is deliberate: a dated id is the same
     * model at the same price, and a table keyed on the exact string silently stops pricing anything
     * the moment a provider pins a new snapshot.
     */
    private val RATES: Map<String, Rates> = mapOf(
        //                       input  cache read  cache write  output
        "claude-opus-5" to Rates(5.00, 0.50, 6.25, 25.00),
        "claude-opus-4-6" to Rates(5.00, 0.50, 6.25, 25.00),
        "claude-haiku-4-5" to Rates(1.00, 0.10, 1.25, 5.00),
        "gpt-5-6-sol" to Rates(5.00, 0.50, 6.25, 22.50),
        "gpt-5-6-luna" to Rates(0.20, 0.02, 0.25, 0.90),
    )

    /** Prices are quoted per million tokens; the counts are per token. */
    private val PER_MILLION = BigDecimal(1_000_000)

    /**
     * Micro-dollars, which is finer than any single request needs and coarse enough to stay exact.
     *
     * A thousand cache reads at the cheapest rate here is $0.00002, so the scale matters for the
     * small requests rather than the large ones: rounding them to cents would record a long tail of
     * requests as free and make the total disagree with the sum of its parts.
     */
    private const val SCALE = 6

    /**
     * What the four counts cost at [model]'s rates, or null when nothing is known about the model.
     *
     * The three input counts are disjoint here, the same as the columns they come from -- see
     * [ModelUsageDatabase]'s note on why the cached ones are not inside `input_tokens`. Charging
     * them all at the input rate would overcharge cache reads tenfold, which is the one arithmetic
     * mistake in this file worth catching.
     */
    fun costUsd(
        model: String,
        inputTokens: Int,
        cacheWriteTokens: Int,
        cacheReadTokens: Int,
        outputTokens: Int,
    ): BigDecimal? {
        val rates = ratesFor(model) ?: return null
        val total = charge(inputTokens, rates.input)
            .add(charge(cacheWriteTokens, rates.cacheWrite))
            .add(charge(cacheReadTokens, rates.cacheRead))
            .add(charge(outputTokens, rates.output))
        // BigDecimal rather than Double: this is money, it is summed across a turn and then across a
        // column, and dividing a rate that is exactly representable in decimal by a million should
        // not introduce a drift that only appears once a few thousand rows have been added up.
        return total.divide(PER_MILLION).setScale(SCALE, RoundingMode.HALF_UP)
    }

    private fun charge(tokens: Int, ratePerMillion: Double): BigDecimal =
        BigDecimal.valueOf(ratePerMillion).multiply(BigDecimal(tokens))

    /**
     * The rates for a model id, matched loosely on purpose.
     *
     * What reaches here is whatever the configuration file asked for, and the same model arrives
     * spelled several ways: dated (`claude-haiku-4-5-20251001`), prefixed by a gateway or a router
     * (`anthropic/claude-opus-5`), or in a hosted provider's own id
     * (`us.anthropic.claude-opus-4-6-v1:0`). Separators are flattened so `4.5` and `4-5` are the same
     * string, and the longest matching key wins so a key that is a prefix of another cannot claim a
     * request belonging to it.
     */
    private fun ratesFor(model: String): Rates? {
        val id = normalise(model)
        if (id.isEmpty()) return null
        return RATES.entries
            .filter { id.contains(it.key) }
            .maxByOrNull { it.key.length }
            ?.value
    }

    private fun normalise(model: String): String =
        model.trim().lowercase().map { if (it == '.' || it == '_') '-' else it }.joinToString("")

    /**
     * A cost as it is shown under a reply.
     *
     * Four decimals rather than two: a single request is routinely under a cent, and a column of
     * `$0.00` under every reply would be worse than showing nothing at all. Anything smaller than
     * the fourth decimal is drawn as `<$0.0001` rather than rounded down to zero, for the same
     * reason -- "too small to show" and "free" are different claims.
     */
    fun format(cost: BigDecimal): String {
        val rounded = cost.setScale(4, RoundingMode.HALF_UP)
        if (rounded.signum() == 0 && cost.signum() > 0) return "<\$0.0001"
        return "\$${rounded.toPlainString()}"
    }

    /** The models this file has a price for, named when one is missing. */
    fun pricedModels(): List<String> = RATES.keys.sorted()
}
