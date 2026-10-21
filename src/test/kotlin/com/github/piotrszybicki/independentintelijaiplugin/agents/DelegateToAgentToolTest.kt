package com.github.piotrszybicki.independentintelijaiplugin.agents

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class DelegateToAgentToolTest {
    private val investigate =
        AgentDefinition(
            name = "investigate-agent",
            description = "Investigates code.",
            prompt = "Investigate the request.",
            origin = AgentDefinition.CONFIGURATION,
        )

    @Test
    fun `prepares a handoff for a configured agent`() {
        val tool = DelegateToAgentTool({ listOf(investigate) }) { agent, task -> "Prepared @${agent.name}: $task" }

        assertEquals(
            "Prepared @investigate-agent: Trace the failing request.",
            tool.execute(arguments("investigate-agent", "Trace the failing request.")),
        )
    }

    @Test
    fun `rejects an unknown agent`() {
        val tool = DelegateToAgentTool({ listOf(investigate) }) { _, _ -> error("must not prepare") }

        assertEquals(
            "Error: @missing-agent is not a configured specialist agent.",
            tool.execute(arguments("missing-agent", "Trace the failing request.")),
        )
    }

    private fun arguments(
        agent: String,
        task: String,
    ) = JsonObject().apply {
        addProperty("agent_name", agent)
        addProperty("task", task)
    }
}
