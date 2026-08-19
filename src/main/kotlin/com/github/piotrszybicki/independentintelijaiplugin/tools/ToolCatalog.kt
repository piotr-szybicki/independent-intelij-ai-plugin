package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool
import com.github.piotrszybicki.independentintelijaiplugin.settings.AICodingAgentSettingsState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

enum class ToolCategory(val displayName: String) {
    READ("Reading code"),
    NAVIGATE("Navigating code"),

    VCS("Version control"),

    EDIT("Editing and fixing"),
    RUN("Running code"),
    DEBUG("Debugging"),

    SUPPORT("Cutting output down"),
}

object ToolCatalog {

    class Entry(
        val name: String,
        val category: ToolCategory,
        val onByDefault: Boolean,
        val create: (Project) -> AICodingAgentTool,
    )

    val entries: List<Entry> = listOf(
        Entry("list_directory", ToolCategory.READ, true, ::ListDirectoryTool),
        Entry("read_project_file", ToolCategory.READ, true, ::ReadProjectFileTool),
        Entry("read_library_class", ToolCategory.READ, true, ::ReadLibraryClassTool),
        Entry("get_file_structure", ToolCategory.READ, true, ::GetFileStructureTool),
        Entry("attach_library_sources", ToolCategory.READ, false, ::AttachLibrarySourcesTool),

        Entry("get_comment", ToolCategory.READ, false, ::GetCommentTool),

        Entry("find_in_files", ToolCategory.NAVIGATE, true, ::FindInFilesTool),
        Entry("find_by_name", ToolCategory.NAVIGATE, true, ::FindByNameTool),
        Entry("find_usages", ToolCategory.NAVIGATE, true, ::FindUsagesTool),
        Entry("find_implementations", ToolCategory.NAVIGATE, true, ::FindImplementationsTool),
        Entry("get_symbol_info", ToolCategory.NAVIGATE, true, ::GetSymbolInfoTool),

        Entry("git_status", ToolCategory.VCS, false, ::GitStatusTool),
        Entry("git_diff", ToolCategory.VCS, false, ::GitDiffTool),
        Entry("git_log", ToolCategory.VCS, false, ::GitLogTool),
        Entry("git_blame", ToolCategory.VCS, false, ::GitBlameTool),

        Entry("get_file_problems", ToolCategory.EDIT, false, ::GetFileProblemsTool),
        Entry("apply_quick_fix", ToolCategory.EDIT, false, ::ApplyQuickFixTool),
        Entry("edit_file_lines", ToolCategory.EDIT, false, ::EditFileLinesTool),
        Entry("create_file", ToolCategory.EDIT, false, ::CreateFileTool),
        Entry("move_file", ToolCategory.EDIT, false, ::MoveFileTool),
        Entry("delete_file", ToolCategory.EDIT, false, ::DeleteFileTool),
        Entry("rename_symbol", ToolCategory.EDIT, false, ::RenameSymbolTool),
        Entry("safe_delete", ToolCategory.EDIT, false, ::SafeDeleteTool),
        Entry("insert_comment", ToolCategory.EDIT, false, ::InsertCommentTool),

        Entry("run_action", ToolCategory.RUN, false, ::RunActionTool),
        Entry("run_shell_command", ToolCategory.RUN, false, ::RunShellCommandTool),

        Entry("toggle_breakpoint", ToolCategory.DEBUG, false, ::ToggleBreakpointTool),
        Entry("start_debug_configuration", ToolCategory.DEBUG, false, ::StartDebugConfigurationTool),
        Entry("await_breakpoint", ToolCategory.DEBUG, false, ::AwaitBreakpointTool),
        Entry("debugger_action", ToolCategory.DEBUG, false, ::DebuggerActionTool),
        Entry("evaluate_expression", ToolCategory.DEBUG, false, ::EvaluateExpressionTool),

        Entry("summarize", ToolCategory.SUPPORT, false, ::SummarizeTool),
    )

    val DEFAULT_ENABLED: String = format(entries.filter { it.onByDefault }.map { it.name })

    fun buildAll(project: Project): List<AICodingAgentTool> = entries.map { entry ->
        entry.create(project).also {
            if (it.name != entry.name) {
                log.error("Tool catalog calls this tool '${entry.name}', but it calls itself '${it.name}'")
            }
        }
    }

    fun enabledIn(tools: List<AICodingAgentTool>): List<AICodingAgentTool> {
        val enabled = parse(AICodingAgentSettingsState.getInstance().state.enabledTools)
        return tools.filter { it.name in enabled }
    }

    fun parse(value: String): Set<String> {
        val known = entries.mapTo(mutableSetOf()) { it.name }
        return value.split(',').map { it.trim() }.filterTo(mutableSetOf()) { it in known }
    }

    fun format(names: Collection<String>): String =
        entries.filter { it.name in names }.joinToString(",") { it.name }

    private val log = Logger.getInstance(ToolCatalog::class.java)
}
