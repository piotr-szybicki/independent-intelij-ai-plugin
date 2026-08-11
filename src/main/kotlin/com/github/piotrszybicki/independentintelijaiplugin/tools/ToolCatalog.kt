package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool
import com.github.piotrszybicki.independentintelijaiplugin.settings.AICodingAgentSettingsState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * What a tool lets the model do. The settings list is grouped by this, and it is also the line the
 * defaults are drawn along: everything past [NAVIGATE] changes something, and is off until asked for.
 */
enum class ToolCategory(val displayName: String) {
    READ("Reading code"),
    NAVIGATE("Navigating code"),

    /** get_file_problems is read-only, but it belongs to the edit/verify loop and is dead weight without it. */
    EDIT("Editing and fixing"),
    RUN("Running code"),
    DEBUG("Debugging"),
}

/**
 * The built-in tools, and which of them a request carries.
 *
 * The single place a built-in tool is declared. Every tool definition is re-sent on every request,
 * so the whole set is a standing cost on each turn whether or not the model uses any of it -- hence
 * the choice, and hence a default of only what it takes to read a project rather than everything.
 */
object ToolCatalog {

    /**
     * One built-in tool.
     *
     * [name] duplicates the tool's own `name` so the settings UI can list the tools without a
     * [Project] to build them with. [buildAll] checks the two agree.
     */
    class Entry(
        val name: String,
        val category: ToolCategory,
        val onByDefault: Boolean,
        val create: (Project) -> AICodingAgentTool,
    )

    val entries: List<Entry> = listOf(
        Entry("get_editor_context", ToolCategory.READ, true, ::GetEditorContextTool),
        Entry("list_open_files", ToolCategory.READ, true, ::ListOpenFilesTool),
        Entry("list_directory", ToolCategory.READ, true, ::ListDirectoryTool),
        Entry("file_exists", ToolCategory.READ, true, ::FileExistsTool),
        Entry("read_project_file", ToolCategory.READ, true, ::ReadProjectFileTool),
        Entry("read_library_class", ToolCategory.READ, true, ::ReadLibraryClassTool),
        Entry("get_file_structure", ToolCategory.READ, true, ::GetFileStructureTool),
        // Off despite being a reading tool: it downloads over the network and rewrites the
        // project's library configuration, which is not something a read-only setup should do.
        Entry("attach_library_sources", ToolCategory.READ, false, ::AttachLibrarySourcesTool),

        Entry("find_in_files", ToolCategory.NAVIGATE, true, ::FindInFilesTool),
        Entry("find_by_name", ToolCategory.NAVIGATE, true, ::FindByNameTool),
        Entry("find_usages", ToolCategory.NAVIGATE, true, ::FindUsagesTool),
        Entry("find_implementations", ToolCategory.NAVIGATE, true, ::FindImplementationsTool),
        Entry("get_symbol_info", ToolCategory.NAVIGATE, true, ::GetSymbolInfoTool),

        Entry("get_file_problems", ToolCategory.EDIT, false, ::GetFileProblemsTool),
        Entry("apply_quick_fix", ToolCategory.EDIT, false, ::ApplyQuickFixTool),
        Entry("edit_file_lines", ToolCategory.EDIT, false, ::EditFileLinesTool),
        Entry("create_file", ToolCategory.EDIT, false, ::CreateFileTool),
        Entry("move_file", ToolCategory.EDIT, false, ::MoveFileTool),
        Entry("delete_file", ToolCategory.EDIT, false, ::DeleteFileTool),
        Entry("rename_symbol", ToolCategory.EDIT, false, ::RenameSymbolTool),
        Entry("safe_delete", ToolCategory.EDIT, false, ::SafeDeleteTool),
        Entry("add_import", ToolCategory.EDIT, false, ::AddImportTool),
        Entry("insert_member", ToolCategory.EDIT, false, ::InsertMemberTool),

        Entry("run_configuration", ToolCategory.RUN, false, ::RunConfigurationTool),
        Entry("run_at_location", ToolCategory.RUN, false, ::RunAtLocationTool),
        Entry("run_action", ToolCategory.RUN, false, ::RunActionTool),
        Entry("run_shell_command", ToolCategory.RUN, false, ::RunShellCommandTool),

        Entry("toggle_breakpoint", ToolCategory.DEBUG, false, ::ToggleBreakpointTool),
        Entry("start_debug_configuration", ToolCategory.DEBUG, false, ::StartDebugConfigurationTool),
        Entry("await_breakpoint", ToolCategory.DEBUG, false, ::AwaitBreakpointTool),
        Entry("debugger_action", ToolCategory.DEBUG, false, ::DebuggerActionTool),
        Entry("evaluate_expression", ToolCategory.DEBUG, false, ::EvaluateExpressionTool),
    )

    /** What a fresh install sends: enough to read a project and find your way round it, and no more. */
    val DEFAULT_ENABLED: String = format(entries.filter { it.onByDefault }.map { it.name })

    /**
     * Builds every built-in tool, enabled or not.
     *
     * All of them, because enabling one mid-conversation should not mean rebuilding the panel --
     * [enabledIn] does the choosing, per turn, over the list this returns.
     */
    fun buildAll(project: Project): List<AICodingAgentTool> = entries.map { entry ->
        entry.create(project).also {
            if (it.name != entry.name) {
                // A programming error, and a quiet one: the settings entry would control nothing and
                // the tool would never be sent. Logged rather than thrown so it does not take the
                // tool window down with it -- Logger.error still fails the build's tests.
                log.error("Tool catalog calls this tool '${entry.name}', but it calls itself '${it.name}'")
            }
        }
    }

    /** The subset of [tools] the user has switched on. Read per turn, so a change lands on the next message. */
    fun enabledIn(tools: List<AICodingAgentTool>): List<AICodingAgentTool> {
        val enabled = parse(AICodingAgentSettingsState.getInstance().state.enabledTools)
        return tools.filter { it.name in enabled }
    }

    /**
     * The names in a saved setting that this version still has tools for.
     *
     * Unknown names are dropped rather than kept: the setting outlives the catalog, and a name left
     * over from a renamed tool would otherwise read as "modified" in the settings dialog forever.
     */
    fun parse(value: String): Set<String> {
        val known = entries.mapTo(mutableSetOf()) { it.name }
        return value.split(',').map { it.trim() }.filterTo(mutableSetOf()) { it in known }
    }

    /** Catalog order rather than selection order, so the saved string does not churn between edits. */
    fun format(names: Collection<String>): String =
        entries.filter { it.name in names }.joinToString(",") { it.name }

    private val log = Logger.getInstance(ToolCatalog::class.java)
}
