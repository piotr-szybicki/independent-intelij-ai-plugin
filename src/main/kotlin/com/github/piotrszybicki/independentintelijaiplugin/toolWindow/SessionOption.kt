package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.github.piotrszybicki.independentintelijaiplugin.settings.ConversationTools

internal enum class SessionOption(
    val command: String,
    val description: String,
    val tools: List<String>,
) {
    CODING_TOOLS(
        "coding-tools",
        "Enable coding tools in this chat",
        // Snapshot of coding-agent's tools. The former diagnostic and quick-fix tools
        // are now provided by analyse_and_fix.
        listOf(
            "list_directory",
            "read_project_file",
            "read_library_class",
            "get_file_structure",
            "find_in_files",
            "find_by_name",
            "find_usages",
            "find_implementations",
            "get_symbol_info",
            "create_file",
            "edit_file_lines",
            "move_file",
            "delete_file",
            "rename_symbol",
            "safe_delete",
            "analyse_and_fix",
            "git_status",
            "git_diff",
            "run_shell_command",
            "summarize",
        ),
    ),
    ;

    fun applyTo(current: ConversationTools): ConversationTools =
        current.copy(overridden = true, tools = tools, mcpTools = emptyList())

    companion object {
        fun fromCommand(text: String): SessionOption? = entries.firstOrNull { text == "+${it.command}" }
    }
}
