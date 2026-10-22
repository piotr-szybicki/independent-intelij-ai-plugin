package com.github.piotrszybicki.independentintelijaiplugin.tools

/** Preserve saved diagnostic-tool selections while exposing only the new repair tool to models. */
object ToolNames {
    fun current(name: String): String = if (name == "get_file_problems") "analyse_and_fix" else name
}
