package com.github.piotrszybicki.independentintelijaiplugin.logging

import java.math.BigDecimal
import java.math.RoundingMode

object ModelPricing {

    data class Rates(
        val input: Double,
        val cacheRead: Double,
        val cacheWrite: Double,
        val output: Double,
    )

    private val RATES: Map<String, Rates> = mapOf(
        "claude-opus-5" to Rates(5.00, 0.50, 6.25, 25.00),
        "claude-opus-4-6" to Rates(5.00, 0.50, 6.25, 25.00),
        "claude-haiku-4-5" to Rates(1.00, 0.10, 1.25, 5.00),
        "gpt-5-6-sol" to Rates(5.00, 0.50, 6.25, 22.50),
        "gpt-5-6-luna" to Rates(0.20, 0.02, 0.25, 0.90),
    )

    private val PER_MILLION = BigDecimal(1_000_000)

    private const val SCALE = 6

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
        return total.divide(PER_MILLION).setScale(SCALE, RoundingMode.HALF_UP)
    }

    private fun charge(tokens: Int, ratePerMillion: Double): BigDecimal =
        BigDecimal.valueOf(ratePerMillion).multiply(BigDecimal(tokens))

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

    fun format(cost: BigDecimal): String {
        val rounded = cost.setScale(4, RoundingMode.HALF_UP)
        if (rounded.signum() == 0 && cost.signum() > 0) return "<\$0.0001"
        return "\$${rounded.toPlainString()}"
    }

    fun pricedModels(): List<String> = RATES.keys.sorted()
}
