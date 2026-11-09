package com.github.piotrszybicki.independentintelijaiplugin.mcp

/** Session choices override configured defaults, including an explicitly empty selection. */
internal class McpToolSelection {
    private var selected: Set<String>? = null

    fun set(names: Collection<String>) {
        selected = names.toSet()
    }

    fun enabled(defaults: Collection<String>): Set<String> = selected ?: defaults.toSet()
}
