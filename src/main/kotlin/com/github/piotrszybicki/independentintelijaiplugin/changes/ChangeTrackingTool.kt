package com.github.piotrszybicki.independentintelijaiplugin.changes

import com.google.gson.JsonObject
import com.github.piotrszybicki.independentintelijaiplugin.anthropic.AnthropicTool

/**
 * Opens a [ChangeSessionService] capture window around a tool call, so any document the tool touches
 * gets its pre-change text recorded.
 *
 * The decorator is pure scoping -- it does not need to know which files the tool will edit, because
 * the service picks those up from the document events themselves. That is what lets it cover
 * `rename_symbol`, whose file set is only known once the refactoring runs.
 */
class ChangeTrackingTool(
    private val delegate: AnthropicTool,
    private val session: ChangeSessionService,
) : AnthropicTool by delegate {

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
        /** Wraps every tool; the read-only ones simply never trigger a capture. */
        fun wrapAll(tools: List<AnthropicTool>, session: ChangeSessionService): List<AnthropicTool> =
            tools.map { ChangeTrackingTool(it, session) }
    }
}
