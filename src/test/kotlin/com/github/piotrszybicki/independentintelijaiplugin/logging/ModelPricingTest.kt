package com.github.piotrszybicki.independentintelijaiplugin.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

class ModelPricingTest {

    private fun cost(model: String, input: Int, cacheWrite: Int, cacheRead: Int, output: Int) =
        ModelPricing.costUsd(model, input, cacheWrite, cacheRead, output)

    @Test
    fun `charges each count at its own rate`() {
        assertEquals(BigDecimal("36.750000"), cost("claude-opus-5", 1_000_000, 1_000_000, 1_000_000, 1_000_000))
        assertEquals(BigDecimal("1.370000"), cost("gpt-5.6-luna", 1_000_000, 1_000_000, 1_000_000, 1_000_000))
    }

    @Test
    fun `prices a typical cached request`() {
        assertEquals(BigDecimal("0.067500"), cost("claude-opus-5", 2_000, 0, 40_000, 1_500))
    }

    @Test
    fun `prices the cheap model cheaply`() {
        assertEquals(BigDecimal("0.006000"), cost("claude-haiku-4-5-20251001", 1_000, 0, 0, 1_000))
        assertEquals(BigDecimal("0.030000"), cost("claude-opus-5", 1_000, 0, 0, 1_000))
    }

    @Test
    fun `matches a dated model id`() {
        assertEquals(cost("claude-haiku-4-5", 10, 10, 10, 10), cost("claude-haiku-4-5-20251001", 10, 10, 10, 10))
    }

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

    @Test
    fun `has no cost for a model it has no price for`() {
        assertNull(cost("qwen3-coder", 10_000, 0, 0, 10_000))
        assertNull(cost("", 10_000, 0, 0, 10_000))
        assertNull(cost("   ", 10_000, 0, 0, 10_000))
    }

    @Test
    fun `prices an empty usage block at zero`() {
        assertEquals(BigDecimal("0.000000"), cost("claude-opus-5", 0, 0, 0, 0))
    }

    @Test
    fun `formats a cost for the line under a reply`() {
        assertEquals("\$0.0675", ModelPricing.format(BigDecimal("0.067500")))
        assertEquals("\$1.2346", ModelPricing.format(BigDecimal("1.23456")))
        assertEquals("\$0.0000", ModelPricing.format(BigDecimal("0.000000")))
    }

    @Test
    fun `does not round a real cost down to nothing`() {
        assertEquals("<\$0.0001", ModelPricing.format(BigDecimal("0.000004")))
    }
}
