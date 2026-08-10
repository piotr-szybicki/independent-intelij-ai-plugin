package com.github.piotrszybicki.independentintelijaiplugin.skills

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.github.piotrszybicki.independentintelijaiplugin.settings.AnthropicSettingsState
import java.io.File

/**
 * Finds the skills under the configured roots and turns them into the block appended to the system
 * prompt.
 *
 * An object rather than a service because there is nothing to own: scanning is a directory listing
 * and the first few kilobytes of some small markdown files, with no process to start and no
 * connection to keep alive. That is cheap enough to redo on every turn, which is what makes an
 * edited or newly added skill take effect on the next message instead of on the next restart --
 * where [com.github.piotrszybicki.independentintelijaiplugin.mcp.McpService] has to cache because
 * connecting is expensive, this can afford to be correct instead.
 *
 * Nothing here throws. An unreadable root or a malformed `SKILL.md` costs the user that one entry,
 * and [statuses] is where it becomes visible.
 */
object SkillCatalog {

    /** One line per configured root, for the settings panel and for diagnosing a skill that never shows up. */
    data class RootStatus(val configured: String, val resolved: String, val skillCount: Int, val error: String?)

    data class Scan(val skills: List<SkillDefinition>, val statuses: List<RootStatus>)

    /**
     * Ceiling on how many skills are listed, in case a root is pointed at a home directory or some
     * other tree that is nothing like a skills folder. Reaching it means the configuration is wrong,
     * so the cap is about not silently filling the context window while that is sorted out.
     */
    private const val MAX_SKILLS = 200

    private const val SKILL_FILE = "SKILL.md"

    private val LOG = Logger.getInstance(SkillCatalog::class.java)

    /** Reads the configured roots and scans them. Touches the filesystem, so never on the EDT. */
    fun scan(project: Project): Scan {
        val text = AnthropicSettingsState.getInstance().state.skillPaths
        return scan(SkillRoot.parseAll(text, project.basePath?.let(::File)))
    }

    /**
     * Scans [roots] in order. The first root to define a name wins, so precedence is the order the
     * user listed them in -- which is the only rule that stays predictable once roots can live
     * anywhere on disk.
     */
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
                // A shadowed skill is not an error -- it is how a project overrides a personal one --
                // so it is dropped quietly rather than reported against the root that lost.
                if (!byName.add(skill.name)) continue
                skills.add(skill)
                found++
            }

            statuses.add(RootStatus(root.configured, directory.path, found, null))
        }

        return Scan(skills, statuses)
    }

    /**
     * The `SKILL.md` files [directory] offers: one per immediate subdirectory, plus the directory's
     * own if it is itself a skill.
     *
     * Only one level down. Descending further would turn a root pointed somewhere too broad into a
     * full-tree walk, and the format puts the entrypoint at a fixed depth anyway.
     */
    private fun skillFilesIn(directory: File): List<File> {
        val children = directory.listFiles()?.sortedBy { it.name.lowercase() }.orEmpty()
        val own = children.filter { it.isFile && it.name.equals(SKILL_FILE, ignoreCase = true) }
        val nested = children.filter { it.isDirectory && !it.isHidden }.mapNotNull { child ->
            child.listFiles()?.firstOrNull { it.isFile && it.name.equals(SKILL_FILE, ignoreCase = true) }
        }
        return own + nested
    }

    /**
     * The block appended to the system prompt, or an empty string when there are no skills.
     *
     * Only the name, the description and where the file is -- the instructions themselves stay on
     * disk until the model decides a skill applies and reads it. A listing entry costs a couple of
     * lines per turn; the body it stands for is frequently thousands of words, and would otherwise
     * be charged for on every message of every conversation whether or not it was ever relevant.
     */
    fun describe(project: Project): String {
        val skills = runCatching { scan(project).skills }.getOrElse {
            LOG.info("Could not scan for skills: ${it.message}")
            return ""
        }
        if (skills.isEmpty()) return ""

        val base = project.basePath?.let(::File)
        return buildString {
            appendLine("Skills available for this project:")
            for (skill in skills) {
                appendLine("- ${skill.name}: ${skill.description}")
                appendLine("  Instructions: ${displayPath(base, skill.file)}")
            }
            appendLine()
            append(
                "A skill is a written procedure for one kind of task. The list above is names and " +
                    "descriptions only -- what a skill actually tells you to do is in its file, " +
                    "which you read when you decide the skill applies. When one matches what the " +
                    "user is asking for, read it and follow it before doing the work, rather than " +
                    "working from its description. A path shown relative to the project root is " +
                    "read with `read_project_file`; an absolute one is outside the project, so " +
                    "`run_shell_command` is what reads it. A skill may name further files beside " +
                    "it -- read those only when its instructions say to.",
            )
        }
    }

    /**
     * Project-relative when the skill is inside the project, absolute when it is not.
     *
     * The difference is not cosmetic: `read_project_file` takes a project-relative path and rejects
     * everything else, so the form the path is printed in is what tells the model which tool can
     * open it.
     */
    private fun displayPath(base: File?, file: File): String {
        if (base == null) return file.path
        val root = runCatching { base.canonicalFile }.getOrDefault(base.absoluteFile)
        val prefix = root.path + File.separator
        if (!file.path.startsWith(prefix)) return file.path
        return file.path.removePrefix(prefix).replace(File.separatorChar, '/')
    }
}
