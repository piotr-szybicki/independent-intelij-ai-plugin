package org.jetbrains.plugins.template.changes

import com.google.gson.JsonObject
import org.jetbrains.plugins.template.anthropic.AnthropicTool

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
            session.endCapture()
        }
    }

    companion object {
        /** Wraps every tool; the read-only ones simply never trigger a capture. */
        fun wrapAll(tools: List<AnthropicTool>, session: ChangeSessionService): List<AnthropicTool> =
            tools.map { ChangeTrackingTool(it, session) }
    }
}
