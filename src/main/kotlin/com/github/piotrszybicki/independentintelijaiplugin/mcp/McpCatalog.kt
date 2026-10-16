package com.github.piotrszybicki.independentintelijaiplugin.mcp

import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool

interface McpCatalog {
    fun tools(): List<AICodingAgentTool>

    fun allTools(): List<AICodingAgentTool>

    fun statuses(): List<McpService.ServerStatus>

    fun reload()
}
