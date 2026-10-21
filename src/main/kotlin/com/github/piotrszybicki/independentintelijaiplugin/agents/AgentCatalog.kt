package com.github.piotrszybicki.independentintelijaiplugin.agents

import com.github.piotrszybicki.independentintelijaiplugin.settings.AgentConfiguration
import com.github.piotrszybicki.independentintelijaiplugin.settings.AgentConfigurations
import com.github.piotrszybicki.independentintelijaiplugin.settings.AgentRosterConfig
import com.github.piotrszybicki.independentintelijaiplugin.settings.AgentRosterEntry
import com.intellij.openapi.project.Project

object AgentCatalog {
    /** Every agent is defined in the JSON configuration named by INTELIJ_AI_SETTINGS. */
    fun all(project: Project): List<AgentDefinition> =
        AgentConfigurations
            .getInstance(project)
            .agents()
            .roster
            .agents
            .map(::definition)

    fun rosterFor(project: Project): AgentRosterConfig = AgentConfigurations.getInstance(project).agents().roster

    private fun definition(entry: AgentRosterEntry) =
        AgentDefinition(
            name = entry.name,
            description = entry.description.ifBlank { "Defined in ${AgentConfiguration.FILE_NAME}." },
            prompt = entry.prompt,
            tools = AgentToolPolicy.of(entry.tools),
            skills = entry.skills,
            specTemplate = entry.specTemplate,
            configurationName = entry.configurationName.ifBlank { null },
            model = entry.model.ifBlank { null },
            origin = AgentDefinition.CONFIGURATION,
        )

    fun find(
        project: Project,
        name: String,
    ): AgentDefinition? = all(project).firstOrNull { it.name == name }

    fun placeholderFor(name: String): AgentDefinition =
        AgentDefinition(
            name = name,
            description = "This agent is no longer defined; the chat keeps its name only.",
            prompt = "",
            origin = AgentDefinition.MISSING,
        )
}
