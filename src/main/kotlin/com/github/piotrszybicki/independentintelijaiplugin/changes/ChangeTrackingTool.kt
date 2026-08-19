package com.github.piotrszybicki.independentintelijaiplugin.changes

import com.google.gson.JsonObject
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool

class ChangeTrackingTool(
    private val delegate: AICodingAgentTool,
    private val session: ChangeSessionService,
) : AICodingAgentTool by delegate {

    override fun execute(input: JsonObject): String {
        session.beginCapture()
        return try {
            delegate.execute(input)
        } finally {
            session.endCapture()

            session.flushToDisk()
        }
    }

    companion object {
        fun wrapAll(tools: List<AICodingAgentTool>, session: ChangeSessionService): List<AICodingAgentTool> =
            tools.map { ChangeTrackingTool(it, session) }
    }
}
