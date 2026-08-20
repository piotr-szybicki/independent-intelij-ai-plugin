package com.github.piotrszybicki.independentintelijaiplugin.agents

import com.github.piotrszybicki.independentintelijaiplugin.settings.AgentConfiguration
import com.github.piotrszybicki.independentintelijaiplugin.settings.AgentConfigurations
import com.github.piotrszybicki.independentintelijaiplugin.settings.AgentRosterConfig
import com.github.piotrszybicki.independentintelijaiplugin.settings.AgentRosterEntry
import com.github.piotrszybicki.independentintelijaiplugin.skills.SkillRoot
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File

object AgentCatalog {

    private const val AGENT_FILE = "AGENT.md"

    private const val MAX_AGENTS = 50

    val SEARCH_PATHS = """
        .agents
        .claude/agents
        ~/.claude/agents
    """.trimIndent()

    private val LOG = Logger.getInstance(AgentCatalog::class.java)

    /*
     * Every agent there is, and there are none this file knows by heart. An agent is either an
     * AGENT.md somewhere on [SEARCH_PATHS] or an entry in the "agents" section of the settings
     * file; nothing is defined in code, so an agent a user has not written does not exist.
     */
    fun all(project: Project): List<AgentDefinition> {
        val found = runCatching { scan(project) }.getOrElse {
            LOG.info("Could not scan for agents: ${it.message}")
            emptyList()
        }
        return withRoster(found, AgentConfigurations.getInstance(project).agents().roster)
    }

    fun rosterFor(project: Project): AgentRosterConfig {
        val written = AgentConfigurations.getInstance(project).agents().roster
        return AgentRosterConfig(all(project).map { rosterEntry(it, written) })
    }

    private fun rosterEntry(agent: AgentDefinition, written: AgentRosterConfig) = AgentRosterEntry(
        name = agent.name,
        description = agent.description,
        prompt = written.forAgent(agent.name)?.prompt.orEmpty(),
        specTemplate = written.forAgent(agent.name)?.specTemplate.orEmpty(),
        tools = toolNames(agent.tools),
        skills = agent.skills,
        configurationName = agent.configurationName.orEmpty(),
        model = agent.model.orEmpty(),
    )

    private fun toolNames(policy: AgentToolPolicy): List<String> = buildList {
        if (policy.takesAll) add("*")
        addAll(policy.allowed.orEmpty().sorted())
        addAll(policy.denied.sorted().map { "-$it" })
    }

    private fun withRoster(defined: List<AgentDefinition>, roster: AgentRosterConfig): List<AgentDefinition> {
        if (roster.agents.isEmpty()) return defined

        val overridden = defined.map { agent ->
            val entry = roster.forAgent(agent.name) ?: return@map agent
            agent.copy(
                description = entry.description.ifBlank { agent.description },
                prompt = entry.prompt.ifBlank { agent.prompt },
                tools = if (entry.tools.isEmpty()) agent.tools else AgentToolPolicy.of(entry.tools),
                skills = entry.skills.ifEmpty { agent.skills },
                specTemplate = entry.specTemplate.ifBlank { agent.specTemplate },
                configurationName = entry.configurationName.ifBlank { null } ?: agent.configurationName,
                model = entry.model.ifBlank { null } ?: agent.model,
            )
        }

        val known = defined.mapTo(mutableSetOf()) { it.name }
        val added = roster.agents.filter { it.name !in known }.map { entry ->
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
        }
        return overridden + added
    }

    fun find(project: Project, name: String): AgentDefinition? =
        all(project).firstOrNull { it.name == name }

    fun placeholderFor(name: String): AgentDefinition =
        AgentDefinition(
            name = name,
            description = "This agent is no longer defined; the chat keeps its name only.",
            prompt = "",
            origin = AgentDefinition.MISSING,
        )

    private fun scan(project: Project): List<AgentDefinition> {
        val roots = SkillRoot.parseAll(SEARCH_PATHS, project.basePath?.let(::File))
        val agents = mutableListOf<AgentDefinition>()
        val byName = mutableSetOf<String>()

        for (root in roots) {
            val directory = root.directory?.takeIf { it.isDirectory } ?: continue
            for (file in agentFilesIn(directory)) {
                if (agents.size >= MAX_AGENTS) {
                    LOG.info("Agent listing capped at $MAX_AGENTS; ${directory.path} has more")
                    return agents
                }
                val agent = AgentDefinition.read(file)
                if (agent == null) {
                    LOG.info("Skipped ${file.path}: no description, or nothing below the frontmatter")
                    continue
                }
                if (!byName.add(agent.name)) continue
                agents += agent
            }
        }
        return agents
    }

    private fun agentFilesIn(directory: File): List<File> {
        val children = directory.listFiles()?.sortedBy { it.name.lowercase() }.orEmpty()
        val loose = children.filter { it.isFile && it.name.endsWith(".md", ignoreCase = true) }
        val nested = children.filter { it.isDirectory && !it.isHidden }.mapNotNull { child ->
            child.listFiles()?.firstOrNull { it.isFile && it.name.equals(AGENT_FILE, ignoreCase = true) }
        }
        return loose + nested
    }
}
