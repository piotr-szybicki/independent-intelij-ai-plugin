package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentUsage
import com.github.piotrszybicki.independentintelijaiplugin.logging.ModelPricing
import java.math.BigDecimal

internal class TurnCostTracker {

    var cost: BigDecimal? = null
        private set
    var model: String = ""
        private set
    var requests: Int = 0
        private set

    fun begin() {
        cost = null
        model = ""
        requests = 0
    }

    fun add(model: String, reported: AICodingAgentUsage): BigDecimal? {
        val requestCost = ModelPricing.costUsd(
            model,
            reported.input_tokens,
            reported.cache_creation_input_tokens,
            reported.cache_read_input_tokens,
            reported.output_tokens,
        ) ?: return null
        val total = (cost ?: BigDecimal.ZERO).add(requestCost)
        cost = total
        this.model = model
        requests++
        return total
    }

    fun take(): Snapshot? {
        val c = cost ?: return null
        val snapshot = Snapshot(c, model, requests)
        cost = null
        return snapshot
    }

    data class Snapshot(val cost: BigDecimal, val model: String, val requests: Int)

    companion object {
        fun label(cost: BigDecimal): String = "≈ ${ModelPricing.format(cost)}"

        fun tooltip(model: String, requests: Int): String = buildString {
            append("<html>Estimated cost of this reply")
            if (model.isNotBlank()) append(" on $model")
            append(", over $requests request(s).<br><br>")
            append("Worked out from the tokens the provider reported, at its published list ")
            append("prices. It is an estimate, not a bill: discounts, negotiated rates and ")
            append("anything charged outside the token counts are not in it.</html>")
        }
    }
}
