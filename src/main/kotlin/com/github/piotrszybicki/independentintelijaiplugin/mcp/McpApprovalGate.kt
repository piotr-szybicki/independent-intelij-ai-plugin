package com.github.piotrszybicki.independentintelijaiplugin.mcp

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.github.piotrszybicki.independentintelijaiplugin.settings.AICodingAgentSettingsState
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

class McpApprovalGate {

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val approveEverything = AtomicBoolean(false)
    private val approvedTools = Collections.synchronizedSet(mutableSetOf<String>())

    fun forget() {
        approveEverything.set(false)
        approvedTools.clear()
    }

    fun confirm(project: Project, toolName: String, server: McpServerConfig, input: JsonObject): Boolean {
        if (!AICodingAgentSettingsState.getInstance().state.confirmMcpToolCalls) return true
        if (approveEverything.get() || approvedTools.contains(toolName)) return true

        val arguments = gson.toJson(input).let { if (it.length > 2_000) it.take(2_000) + "\n[...]" else it }
        val where = if (server.isStdio) {
            "It runs as a local process (${server.target}) with your account's permissions."
        } else {
            "The arguments are sent to ${server.url}, which is outside this machine."
        }

        var choice = -1
        ApplicationManager.getApplication().invokeAndWait {
            choice = Messages.showDialog(
                project,
                "The AI wants to call \"${toolName}\" on the MCP server \"${server.name}\":\n\n$arguments\n\n$where",
                "Run MCP Tool?",
                arrayOf("Run", "Always Run This Tool", "Always Run Any MCP Tool", "Don't Run"),
                3,
                Messages.getWarningIcon(),
            )
        }

        when (choice) {
            1 -> approvedTools.add(toolName)
            2 -> approveEverything.set(true)
        }
        return choice in 0..2
    }
}
