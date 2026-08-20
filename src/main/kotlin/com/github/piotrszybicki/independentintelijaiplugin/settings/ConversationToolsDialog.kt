package com.github.piotrszybicki.independentintelijaiplugin.settings

import com.github.piotrszybicki.independentintelijaiplugin.skills.SkillDefinition
import com.github.piotrszybicki.independentintelijaiplugin.tools.ToolCatalog
import com.github.piotrszybicki.independentintelijaiplugin.tools.ToolCategory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Picks the tools, MCP tools and skills for one chat.
 *
 * The tool boxes start on whatever that chat is using now -- its own selection if it has one,
 * otherwise the inherited set -- so the first click edits what is actually in force rather than an
 * empty slate. Touching one turns the chat's own selection on; clearing the checkbox at the top
 * gives the inherited set back.
 *
 * The skill boxes are not part of that: skills are off in every new chat and are only ever this
 * chat's own choice, so they start on exactly what the chat has and nothing switches them on
 * behind the user's back.
 */
class ConversationToolsDialog(
    project: Project?,
    private val mcpEntries: List<McpToolSelectionDialog.McpToolEntry>,
    private val skills: List<SkillDefinition>,
    initial: ConversationTools,
    private val inherited: ConversationTools,
) : DialogWrapper(project) {

    private val start = if (initial.overridden) initial else inherited

    private val customBox = JBCheckBox("Use a tool set chosen for this chat only", initial.overridden)

    private val toolBoxes: Map<String, JBCheckBox> = ToolCatalog.entries.associate { entry ->
        entry.name to JBCheckBox(entry.name, entry.name in start.toolNames)
    }

    private val categoryMasters: Map<ToolCategory, JBCheckBox> =
        ToolCategory.entries.associateWith { JBCheckBox(it.displayName) }

    private val mcpBoxes: Map<String, JBCheckBox> = mcpEntries.associate { entry ->
        entry.qualifiedName to JBCheckBox(entry.toolName, entry.qualifiedName in start.mcpToolNames)
    }

    private val servers = mcpEntries.map { it.serverName }.distinct()

    private val serverMasters: Map<String, JBCheckBox> = servers.associateWith { JBCheckBox(it) }

    private val activeSkills = initial.activeSkills

    private val skillBoxes: Map<String, JBCheckBox> = skills.associate { skill ->
        skill.name to JBCheckBox(skill.name, skill.name in activeSkills)
    }

    private val tabs = JBTabbedPane()

    private val summary = JBLabel().apply { font = JBFont.small() }

    val selection: ConversationTools
        get() = ConversationTools(
            overridden = customBox.isSelected,
            tools = ToolCatalog.entries.map { it.name }.filter { toolBoxes.getValue(it).isSelected },
            mcpTools = mcpEntries.map { it.qualifiedName }.distinct()
                .filter { mcpBoxes.getValue(it).isSelected },
            skills = skills.map { it.name }.filter { skillBoxes.getValue(it).isSelected },
        )

    init {
        title = "Tools for This Chat"
        setOKButtonText("Use These Tools")
        wireListeners()
        init()
        syncMasters()
        refreshSummary()
    }

    override fun createCenterPanel(): JComponent {
        tabs.addTab("Tools", scrolled(toolsTab()))
        tabs.addTab("MCP tools", scrolled(mcpTab()))
        tabs.addTab("Skills", scrolled(skillsTab()))

        val top = panel {
            row { cell(customBox) }
            row {
                comment(
                    "Off, this chat takes whatever tools the settings page enables, narrowed by " +
                        "the agent's own tool list. On, the Tools and MCP tools tabs are the whole " +
                        "of what this chat may call. Other chats are unaffected, and the choice is " +
                        "saved with this one. Skills are separate -- see that tab.",
                )
            }
        }

        return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            border = JBUI.Borders.emptyBottom(4)
            add(top, BorderLayout.NORTH)
            add(tabs, BorderLayout.CENTER)
            add(summary, BorderLayout.SOUTH)
            preferredSize = JBUI.size(560, 620)
        }
    }

    private fun scrolled(content: JComponent): JComponent =
        JBScrollPane(content).apply { border = JBUI.Borders.empty() }

    private fun toolsTab(): JComponent = panel {
        row {
            button("Read-Only Defaults") { selectTools(ToolCatalog.parse(ToolCatalog.DEFAULT_ENABLED)) }
            button("Select All") { selectTools(ToolCatalog.entries.mapTo(mutableSetOf()) { it.name }) }
            button("Clear") { selectTools(emptySet()) }
        }
        for (category in ToolCategory.entries) {
            group {
                row { cell(categoryMasters.getValue(category)) }
                indent {
                    for (entry in ToolCatalog.entries.filter { it.category == category }) {
                        row { cell(toolBoxes.getValue(entry.name)) }
                    }
                }
            }
        }
    }

    private fun mcpTab(): JComponent = panel {
        if (mcpEntries.isEmpty()) {
            row {
                comment(
                    "No MCP server is connected, so there is nothing to choose here. Servers are " +
                        "configured on the settings page.",
                )
            }
            return@panel
        }
        row {
            button("Select All") { selectMcp(mcpEntries.mapTo(mutableSetOf()) { it.qualifiedName }) }
            button("Clear") { selectMcp(emptySet()) }
        }
        for (server in servers) {
            group {
                row { cell(serverMasters.getValue(server)) }
                indent {
                    for (entry in mcpEntries.filter { it.serverName == server }) {
                        row { cell(mcpBoxes.getValue(entry.qualifiedName)) }
                            .rowComment(shortened(entry.description, "No description provided."))
                    }
                }
            }
        }
    }

    private fun skillsTab(): JComponent = panel {
        row {
            comment(
                "Every chat starts with no skills, whatever the settings page lists. Tick one to " +
                    "let this chat see it -- the same thing typing / at the start of a message " +
                    "does. Only a ticked skill's name and description are sent with each message, " +
                    "and only then can the assistant read and follow it.",
            )
        }
        if (skills.isEmpty()) {
            row {
                comment(
                    "No skills were found in the directories on the settings page, so there is " +
                        "nothing to switch on here.",
                )
            }
            return@panel
        }
        row {
            button("Select All") { selectSkills(skills.mapTo(mutableSetOf()) { it.name }) }
            button("Clear") { selectSkills(emptySet()) }
        }
        for (skill in skills) {
            row { cell(skillBoxes.getValue(skill.name)) }
                .rowComment(shortened(skill.description, "No description."))
        }
    }

    private fun shortened(description: String, ifBlank: String): String {
        val trimmed = description.trim()
        return StringUtil.escapeXmlEntities(
            if (trimmed.length > 200) trimmed.take(200) + "…" else trimmed,
        ).ifBlank { ifBlank }
    }

    private fun wireListeners() {
        customBox.addActionListener {
            if (!customBox.isSelected) showInheritedTools()
            refreshSummary()
        }
        categoryMasters.forEach { (category, master) ->
            master.addActionListener {
                boxesIn(category).forEach { it.isSelected = master.isSelected }
                toolEdited()
            }
        }
        serverMasters.forEach { (server, master) ->
            master.addActionListener {
                boxesIn(server).forEach { it.isSelected = master.isSelected }
                toolEdited()
            }
        }
        (toolBoxes.values + mcpBoxes.values).forEach { box ->
            box.addActionListener { toolEdited() }
        }
        skillBoxes.values.forEach { box ->
            box.addActionListener { refreshSummary() }
        }
    }

    /* Any change to the tool boxes is this chat asking for its own set, so it stops inheriting. */
    private fun toolEdited() {
        customBox.isSelected = true
        syncMasters()
        refreshSummary()
    }

    private fun showInheritedTools() {
        toolBoxes.forEach { (name, box) -> box.isSelected = name in inherited.toolNames }
        mcpBoxes.forEach { (name, box) -> box.isSelected = name in inherited.mcpToolNames }
        syncMasters()
    }

    private fun selectTools(names: Set<String>) {
        toolBoxes.forEach { (name, box) -> box.isSelected = name in names }
        toolEdited()
    }

    private fun selectMcp(names: Set<String>) {
        mcpBoxes.forEach { (name, box) -> box.isSelected = name in names }
        toolEdited()
    }

    private fun selectSkills(names: Set<String>) {
        skillBoxes.forEach { (name, box) -> box.isSelected = name in names }
        refreshSummary()
    }

    private fun boxesIn(category: ToolCategory): List<JBCheckBox> =
        ToolCatalog.entries.filter { it.category == category }.map { toolBoxes.getValue(it.name) }

    private fun boxesIn(server: String): List<JBCheckBox> =
        mcpEntries.filter { it.serverName == server }.map { mcpBoxes.getValue(it.qualifiedName) }

    private fun syncMasters() {
        categoryMasters.forEach { (category, master) ->
            master.isSelected = boxesIn(category).all { it.isSelected }
        }
        serverMasters.forEach { (server, master) ->
            master.isSelected = boxesIn(server).all { it.isSelected }
        }
    }

    private fun refreshSummary() {
        val chosen = selection
        val tools = "${chosen.tools.size}/${toolBoxes.size} tools, ${chosen.mcpTools.size}/${mcpBoxes.size} MCP tools"
        summary.text = if (customBox.isSelected) {
            "This chat only: $tools. Skills on: ${chosen.skills.size}/${skillBoxes.size}."
        } else {
            "Inheriting: $tools. Skills on: ${chosen.skills.size}/${skillBoxes.size}."
        }
        if (tabs.tabCount == 3) {
            tabs.setTitleAt(0, "Tools (${chosen.tools.size})")
            tabs.setTitleAt(1, "MCP tools (${chosen.mcpTools.size})")
            tabs.setTitleAt(2, "Skills (${chosen.skills.size})")
        }
    }
}
