package com.github.piotrszybicki.independentintelijaiplugin.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

/**
 * The figure drawn under every reply and written to `cost_usd`, which are the same call.
 *
 * The arithmetic is four multiplications and cannot really be got wrong; what can is charging the
 * cache columns at the wrong rate, which is off by a factor of ten in the direction that makes a
 * cache look useless, and matching the wrong model, which is off by whatever the two models differ
 * by and shows up as nothing at all. Neither is visible in a row or in a label, so both are pinned
 * here.
 */
class ModelPricingTest {

    private fun cost(model: String, input: Int, cacheWrite: Int, cacheRead: Int, output: Int) =
        ModelPricing.costUsd(model, input, cacheWrite, cacheRead, output)

    /** One million of each, so every rate appears in the answer as itself. */
    @Test
    fun `charges each count at its own rate`() {
        // Opus 5: 5.00 + 6.25 + 0.50 + 25.00
        assertEquals(BigDecimal("36.750000"), cost("claude-opus-5", 1_000_000, 1_000_000, 1_000_000, 1_000_000))
        // Luna: 0.20 + 0.25 + 0.02 + 0.90
        assertEquals(BigDecimal("1.370000"), cost("gpt-5.6-luna", 1_000_000, 1_000_000, 1_000_000, 1_000_000))
    }

    /** The shape of a real request: mostly cache reads, which is the point of the separate rate. */
    @Test
    fun `prices a typical cached request`() {
        // 2,000 input at $5, 40,000 cache reads at $0.50, 1,500 output at $25.
        assertEquals(BigDecimal("0.067500"), cost("claude-opus-5", 2_000, 0, 40_000, 1_500))
    }

    /** Haiku is a tenth of Opus on input, and the ids must not be confused for one another. */
    @Test
    fun `prices the cheap model cheaply`() {
        assertEquals(BigDecimal("0.006000"), cost("claude-haiku-4-5-20251001", 1_000, 0, 0, 1_000))
        assertEquals(BigDecimal("0.030000"), cost("claude-opus-5", 1_000, 0, 0, 1_000))
    }

    /** A dated snapshot is the same model at the same price, and is what a provider actually pins. */
    @Test
    fun `matches a dated model id`() {
        assertEquals(cost("claude-haiku-4-5", 10, 10, 10, 10), cost("claude-haiku-4-5-20251001", 10, 10, 10, 10))
    }

    /** Ids arrive spelled several ways depending on what is in front of the provider. */
    @Test
    fun `matches ids that carry a prefix or a different separator`() {
        val expected = cost("claude-opus-4-6", 1_000, 0, 0, 1_000)
        assertEquals(expected, cost("anthropic/claude-opus-4-6", 1_000, 0, 0, 1_000))
        assertEquals(expected, cost("us.anthropic.claude-opus-4-6-v1:0", 1_000, 0, 0, 1_000))
        assertEquals(expected, cost("Claude-Opus-4.6", 1_000, 0, 0, 1_000))
    }

    @Test
    fun `matches the OpenAI models under either spelling`() {
        assertNotNull(cost("gpt-5.6-sol", 1, 1, 1, 1))
        assertNotNull(cost("gpt-5-6-luna", 1, 1, 1, 1))
    }

    /**
     * Null and not zero: a model with no price recorded as free is a claim, and the wrong one for
     * the case that matters -- a model newer than the table. Nothing is drawn under the reply and
     * the column stays NULL.
     */
    @Test
    fun `has no cost for a model it has no price for`() {
        assertNull(cost("qwen3-coder", 10_000, 0, 0, 10_000))
        assertNull(cost("", 10_000, 0, 0, 10_000))
        assertNull(cost("   ", 10_000, 0, 0, 10_000))
    }

    /** A request that reported a usage block of zeros is still priced, at zero. */
    @Test
    fun `prices an empty usage block at zero`() {
        assertEquals(BigDecimal("0.000000"), cost("claude-opus-5", 0, 0, 0, 0))
    }

    /** Four decimals, because a single reply is routinely worth less than a cent. */
    @Test
    fun `formats a cost for the line under a reply`() {
        assertEquals("\$0.0675", ModelPricing.format(BigDecimal("0.067500")))
        assertEquals("\$1.2346", ModelPricing.format(BigDecimal("1.23456")))
        assertEquals("\$0.0000", ModelPricing.format(BigDecimal("0.000000")))
    }

    /**
     * Something too small to show is not the same claim as nothing at all, and a tool-call round
     * trip on a cheap model really does land below the fourth decimal.
     */
    @Test
    fun `does not round a real cost down to nothing`() {
        assertEquals("<\$0.0001", ModelPricing.format(BigDecimal("0.000004")))
    }
}
