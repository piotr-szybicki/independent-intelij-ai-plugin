package com.github.piotrszybicki.independentintelijaiplugin.skills

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.github.piotrszybicki.independentintelijaiplugin.settings.AICodingAgentSettingsState
import java.io.File

object SkillCatalog {

    data class RootStatus(val configured: String, val resolved: String, val skillCount: Int, val error: String?)

    data class Scan(val skills: List<SkillDefinition>, val statuses: List<RootStatus>)

    private const val MAX_SKILLS = 200

    private const val SKILL_FILE = "SKILL.md"

    private val LOG = Logger.getInstance(SkillCatalog::class.java)

    fun scan(project: Project): Scan {
        val text = AICodingAgentSettingsState.getInstance().state.skillPaths
        return scan(SkillRoot.parseAll(text, project.basePath?.let(::File)))
    }

    fun scan(roots: List<SkillRoot>): Scan {
        val skills = mutableListOf<SkillDefinition>()
        val byName = mutableSetOf<String>()
        val statuses = mutableListOf<RootStatus>()

        for (root in roots) {
            val directory = root.directory
            if (directory == null) {
                statuses.add(RootStatus(root.configured, "", 0, root.error ?: "unusable path"))
                continue
            }
            if (!directory.isDirectory) {
                statuses.add(RootStatus(root.configured, directory.path, 0, "does not exist"))
                continue
            }

            var found = 0
            for (file in skillFilesIn(directory)) {
                if (skills.size >= MAX_SKILLS) {
                    LOG.info("Skill listing capped at $MAX_SKILLS; ${directory.path} has more")
                    break
                }
                val skill = SkillDefinition.read(file)
                if (skill == null) {
                    LOG.info("Skipped ${file.path}: no description and no readable body")
                    continue
                }
                if (!byName.add(skill.name)) continue
                skills.add(skill)
                found++
            }

            statuses.add(RootStatus(root.configured, directory.path, found, null))
        }

        return Scan(skills, statuses)
    }

    private fun skillFilesIn(directory: File): List<File> {
        val children = directory.listFiles()?.sortedBy { it.name.lowercase() }.orEmpty()
        val own = children.filter { it.isFile && it.name.equals(SKILL_FILE, ignoreCase = true) }
        val nested = children.filter { it.isDirectory && !it.isHidden }.mapNotNull { child ->
            child.listFiles()?.firstOrNull { it.isFile && it.name.equals(SKILL_FILE, ignoreCase = true) }
        }
        return own + nested
    }

    /**
     * The skills the settings page leaves enabled, out of [scanned]. A blank selection there means
     * every skill, which is why an empty list is not the same as none.
     */
    fun enabledIn(scanned: List<SkillDefinition>): List<SkillDefinition> {
        val enabled = AICodingAgentSettingsState.getInstance().state.enabledSkills
        if (enabled.isBlank()) return scanned
        val enabledSet = enabled.split(",").mapTo(mutableSetOf()) { it.trim() }
        return scanned.filter { it.name in enabledSet }
    }

    /**
     * [active] is the set of skills one chat has been given -- by the dialog or the / popup -- and
     * nothing outside it is described to the model. An empty set means no skills at all, which is
     * where every chat starts.
     */
    fun describe(project: Project, active: Set<String>): String {
        if (active.isEmpty()) return ""
        val allSkills = runCatching { scan(project).skills }.getOrElse {
            LOG.info("Could not scan for skills: ${it.message}")
            return ""
        }
        val skills = allSkills.filter { it.name in active }
        if (skills.isEmpty()) return ""

        val base = project.basePath?.let(::File)
        return buildString {
            appendLine("Skills the user has switched on for this chat:")
            for (skill in skills) {
                appendLine("- ${skill.name}: ${skill.description}")
                appendLine("  Instructions: ${displayPath(base, skill.file)}")
            }
            appendLine()
            append(
                "A skill is a written procedure for one kind of task. Each one above was switched " +
                    "on for this chat deliberately, so treat it as the user pointing at it. The " +
                    "list is names and descriptions only -- what a skill actually tells you to do " +
                    "is in its file, which you read when you decide the skill applies. When one " +
                    "matches what the user is asking for, read it and follow it before doing the " +
                    "work, rather than working from its description. A path shown relative to the " +
                    "project root is read with `read_project_file`; an absolute one is outside the " +
                    "project, so `run_shell_command` is what reads it. A skill may name further " +
                    "files beside it -- read those only when its instructions say to.",
            )
        }
    }

    /** How a skill's file is named to the model: project-relative where it can be, absolute otherwise. */
    fun displayPath(project: Project, file: File): String = displayPath(project.basePath?.let(::File), file)

    private fun displayPath(base: File?, file: File): String {
        if (base == null) return file.path
        val root = runCatching { base.canonicalFile }.getOrDefault(base.absoluteFile)
        val prefix = root.path + File.separator
        if (!file.path.startsWith(prefix)) return file.path
        return file.path.removePrefix(prefix).replace(File.separatorChar, '/')
    }
}
