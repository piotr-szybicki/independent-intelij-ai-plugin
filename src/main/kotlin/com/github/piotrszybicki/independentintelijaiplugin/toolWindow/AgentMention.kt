package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

internal object AgentMention {
    data class Request(
        val name: String,
        val instructions: String,
    )

    fun parse(
        text: String,
        names: Collection<String>,
    ): Request? {
        val input = text.trim()
        val name =
            names.sortedByDescending { it.length }.firstOrNull { candidate ->
                val mention = "@$candidate"
                input.startsWith(mention) && (input.length == mention.length || input[mention.length].isWhitespace())
            } ?: return null
        return Request(name, input.substring(name.length + 1).trim())
    }
}
