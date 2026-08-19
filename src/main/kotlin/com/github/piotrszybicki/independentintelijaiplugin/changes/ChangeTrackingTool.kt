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
            // Closed before the flush so that save-time work -- a reformat-on-save, a trailing
            // newline being added -- is not itself captured as a change the model made.
            session.endCapture()

            // Every tool, not just the editing ones: a tool that edits nothing has nothing unsaved
            // to write, and this way a tool that reaches disk indirectly -- run_shell_command,
            // run_configuration -- still sees the previous tool's edits on disk before it runs.
            session.flushToDisk()
        }
    }

    companion object {
        fun wrapAll(tools: List<AICodingAgentTool>, session: ChangeSessionService): List<AICodingAgentTool> =
            tools.map { ChangeTrackingTool(it, session) }
    }
}
