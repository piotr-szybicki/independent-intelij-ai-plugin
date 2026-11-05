package com.github.piotrszybicki.independentintelijaiplugin.changes

import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.ToolExecutionContext
import com.google.gson.JsonObject

class ChangeTrackingTool(
    private val delegate: AICodingAgentTool,
    private val session: ChangeSessionService,
) : AICodingAgentTool by delegate {
    override fun execute(input: JsonObject): String = capture { delegate.execute(input) }

    override fun execute(
        input: JsonObject,
        context: ToolExecutionContext,
    ): String = capture { delegate.execute(input, context) }

    private fun capture(action: () -> String): String {
        session.beginCapture()
        return try {
            action()
        } finally {
            session.endCapture()

            session.flushToDisk()
        }
    }

    companion object {
        fun wrapAll(
            tools: List<AICodingAgentTool>,
            session: ChangeSessionService,
        ): List<AICodingAgentTool> = tools.map { ChangeTrackingTool(it, session) }
    }
}
