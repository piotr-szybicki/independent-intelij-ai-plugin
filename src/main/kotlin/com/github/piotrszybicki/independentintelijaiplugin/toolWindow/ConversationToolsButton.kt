package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool
import com.github.piotrszybicki.independentintelijaiplugin.mcp.McpTool
import com.github.piotrszybicki.independentintelijaiplugin.settings.ConversationTools
import com.github.piotrszybicki.independentintelijaiplugin.settings.ConversationToolsDialog
import com.github.piotrszybicki.independentintelijaiplugin.settings.McpToolSelectionDialog
import com.github.piotrszybicki.independentintelijaiplugin.skills.SkillCatalog
import com.github.piotrszybicki.independentintelijaiplugin.skills.SkillDefinition
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.util.ui.JBFont
import javax.swing.JButton
import javax.swing.JComponent

/**
 * The chat header's way in to [ConversationToolsDialog]. Connecting to MCP servers and scanning the
 * skill directories can both take a moment, so both happen under a progress dialog rather than on
 * the event thread.
 */
internal class ConversationToolsButton(
    private val project: Project,
    private val mcpTools: () -> List<AICodingAgentTool>,
    private val inherited: () -> ConversationTools,
    private val onChanged: (ConversationTools) -> Unit,
) {

    var selection: ConversationTools = ConversationTools.INHERIT
        private set

    private val button = JButton().apply {
        font = JBFont.small()
        isFocusable = false
        addActionListener { choose() }
    }

    val component: JComponent = button

    init {
        refresh()
    }

    /** Puts a chat's saved selection on the button without reporting it back as a change. */
    fun set(value: ConversationTools) {
        selection = value
        refresh()
    }

    private fun choose() {
        var entries = emptyList<McpToolSelectionDialog.McpToolEntry>()
        var skills = emptyList<SkillDefinition>()

        val task = object : Task.Modal(project, "Gathering This Chat's Tools", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                entries = runCatching { mcpEntries() }.getOrDefault(emptyList())
                skills = runCatching { SkillCatalog.enabledIn(SkillCatalog.scan(project).skills) }
                    .getOrDefault(emptyList())
            }

            override fun onSuccess() {
                val dialog = ConversationToolsDialog(
                    project = project,
                    mcpEntries = entries,
                    skills = skills,
                    initial = selection,
                    inherited = inherited(),
                )
                if (!dialog.showAndGet()) return
                selection = dialog.selection
                refresh()
                onChanged(selection)
            }
        }
        ProgressManager.getInstance().run(task)
    }

    private fun mcpEntries(): List<McpToolSelectionDialog.McpToolEntry> =
        mcpTools().filterIsInstance<McpTool>().map { tool ->
            McpToolSelectionDialog.McpToolEntry(
                serverName = tool.serverName,
                toolName = tool.toolName,
                qualifiedName = tool.name,
                description = tool.toolDescription,
            )
        }

    private fun refresh() {
        button.text = selection.buttonText()
        button.font = if (selection.isCustom) JBFont.small().asBold() else JBFont.small()
        button.foreground = if (selection.isCustom) ChatColors.accent else ChatColors.foreground
        button.toolTipText = "<html>" + selection.describe() +
            "<br/>Click to choose the tools, MCP tools and skills for this chat alone.</html>"
    }
}
