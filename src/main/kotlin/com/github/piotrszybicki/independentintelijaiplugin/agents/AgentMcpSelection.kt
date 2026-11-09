package com.github.piotrszybicki.independentintelijaiplugin.agents

/** MCP selectors are qualified tool names, server wildcards, or a wildcard for all servers. */
internal object AgentMcpSelection {
    fun includes(
        selectors: Collection<String>,
        server: String,
        qualifiedName: String,
    ): Boolean = "*" in selectors || "$server/*" in selectors || qualifiedName in selectors
}
