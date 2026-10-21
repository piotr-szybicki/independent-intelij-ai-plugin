package com.github.piotrszybicki.independentintelijaiplugin.agents

import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Lets the main chat prepare a human-reviewed hand-off to one configured specialist agent.
 */
class DelegateToAgentTool(
    private val agents: () -> List<AgentDefinition>,
    private val prepare: (AgentDefinition, String) -> String,
) : AICodingAgentTool {
    override val name = "delegate_to_agent"

    override val description =
        "Prepares a draft specification for a configured specialist agent. The hand-off is not " +
            "started automatically: the user reviews the generated spec and must press Proceed. " +
            "Use this for a bounded investigation, review, debugging task, or implementation task " +
            "that benefits from a focused separate chat."

    override val inputSchema: JsonObject =
        JsonObject().apply {
            addProperty("type", "object")
            add(
                "properties",
                JsonObject().apply {
                    add(
                        "agent_name",
                        JsonObject().apply {
                            addProperty("type", "string")
                            addProperty("description", "The exact name of a configured specialist agent.")
                        },
                    )
                    add(
                        "task",
                        JsonObject().apply {
                            addProperty("type", "string")
                            addProperty(
                                "description",
                                "A self-contained specification for the agent: goal, relevant context, " +
                                    "constraints, and what its final report should include.",
                            )
                        },
                    )
                },
            )
            add(
                "required",
                JsonArray().apply {
                    add("agent_name")
                    add("task")
                },
            )
        }

    override fun execute(input: JsonObject): String {
        val agentName =
            input
                .get("agent_name")
                ?.asString
                ?.trim()
                .orEmpty()
        if (agentName.isEmpty()) return "Error: missing agent_name."

        val task =
            input
                .get("task")
                ?.asString
                ?.trim()
                .orEmpty()
        if (task.isEmpty()) return "Error: missing task."

        val agent =
            agents()
                .firstOrNull { it.name == agentName }
                ?: return "Error: @$agentName is not a configured specialist agent."
        if (agent.prompt.isBlank()) {
            return "Error: @$agentName has no prompt, so it cannot be started."
        }
        return prepare(agent, task)
    }
}
