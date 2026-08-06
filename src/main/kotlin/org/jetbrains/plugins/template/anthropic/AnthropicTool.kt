package org.jetbrains.plugins.template.anthropic

import com.google.gson.JsonObject

interface AnthropicTool {
    val name: String
    val description: String

    /** JSON Schema (draft-07 subset) describing the tool's input, per the Anthropic tool-use spec. */
    val inputSchema: JsonObject

    /**
     * Whether interrupting this tool's thread actually stops the work it started.
     *
     * False for tools that hand the work to something outside the plugin -- a command running in
     * the IDE terminal keeps running whatever this thread does -- so the UI can say so rather than
     * offering a Stop button that would only stop the waiting.
     */
    val interruptible: Boolean get() = true

    /** Executes the tool with the arguments Claude supplied and returns the text result to send back. */
    fun execute(input: JsonObject): String

    fun toDefinition() = ToolDefinition(name, description, inputSchema)
}
