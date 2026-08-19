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

    const val CODING_AGENT = "coding-agent"

    const val REVIEW_AGENT = "review-agent"

    private const val AGENT_FILE = "AGENT.md"

    private const val MAX_AGENTS = 50

    val SEARCH_PATHS = """
        .agents
        .claude/agents
        ~/.claude/agents
    """.trimIndent()

    val READING_TOOLS = listOf(
        "list_directory", "read_project_file", "read_library_class", "get_file_structure",
        "find_in_files", "find_by_name", "find_usages", "find_implementations", "get_symbol_info",
    )

    val WRITING_TOOLS = listOf(
        "create_file", "edit_file_lines", "move_file", "delete_file",
        "rename_symbol", "safe_delete", "apply_quick_fix",
    )

    private val LOG = Logger.getInstance(AgentCatalog::class.java)

    val BUILT_IN: List<AgentDefinition> = listOf(
        AgentDefinition(
            name = CODING_AGENT,
            description = "Implements an agreed specification: writes the code, runs it, reports what it did.",
            prompt = """
                You are a coding agent. The message you were started with is a specification that a
                user and another model agreed on, and it is the whole of your brief: implement it.

                Work from the specification, not from a guess at what was meant. Where it is
                explicit, follow it exactly. Where it is silent on something you must decide, pick
                what the surrounding code already does and say in your final message which
                decisions you made that the specification did not cover.

                Do the work rather than describing it. Read what you are changing before you change
                it, make the edits, and check them -- compile errors, the file's problems, the test
                or run configuration that covers the change, whichever the project makes available
                to you. Do not end a turn on a statement of intent.

                Finish with a short report: what you changed, file by file; what you verified and
                how; and anything in the specification you could not do, said plainly rather than
                left out.
            """.trimIndent(),
            specTemplate = """
                # What to build

                Describe the change here.

                # Constraints

                # Done when
            """.trimIndent(),
            tools = AgentToolPolicy.of(
                READING_TOOLS + WRITING_TOOLS +
                    listOf("get_file_problems", "git_status", "git_diff", "run_shell_command"),
            ),
        ),
        AgentDefinition(
            name = REVIEW_AGENT,
            description = "Reviews the code against a specification and reports findings. Reads only, never edits.",
            prompt = """
                You are a review agent. The message you were started with is a specification, and
                your brief is to judge the code in this project against it -- not to change
                anything. You have reading and navigation tools only; there is nothing to edit
                with, by design.

                Read the code that the specification touches before saying anything about it.
                Report what you actually found: where the code and the specification disagree,
                where the specification is ambiguous enough that the code could not have been
                right, and what looks correct. Give the file and line for every finding.

                Rank findings by what they would cost if left alone. Say when you are unsure
                rather than padding the list.
            """.trimIndent(),
            tools = AgentToolPolicy.of(
                READING_TOOLS +
                    listOf("get_comment", "git_status", "git_diff", "git_log", "git_blame", "get_file_problems"),
            ),
            specTemplate = """
                # What to review

                # What it has to satisfy
            """.trimIndent(),
        ),
    )

    fun all(project: Project): List<AgentDefinition> {
        val found = runCatching { scan(project) }.getOrElse {
            LOG.info("Could not scan for agents: ${it.message}")
            emptyList()
        }
        val names = found.mapTo(mutableSetOf()) { it.name }
        val defined = found + BUILT_IN.filter { it.name !in names }
        return withRoster(defined, AgentConfigurations.getInstance(project).agents().roster)
    }

    fun rosterFor(project: Project): AgentRosterConfig {
        val written = AgentConfigurations.getInstance(project).agents().roster
        return AgentRosterConfig(all(project).map { rosterEntry(it, written) })
    }

    private fun rosterEntry(agent: AgentDefinition, written: AgentRosterConfig) = AgentRosterEntry(
        name = agent.name,
        description = agent.description,
        prompt = written.forAgent(agent.name)?.prompt.orEmpty(),
        tools = toolNames(agent.tools),
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
