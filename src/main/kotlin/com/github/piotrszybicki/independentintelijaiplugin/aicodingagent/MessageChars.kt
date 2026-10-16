package com.github.piotrszybicki.independentintelijaiplugin.aicodingagent

object MessageChars {
    fun of(messages: List<ChatMessage>): Int = messages.sumOf { it.content.toString().length }
}
