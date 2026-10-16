package com.github.piotrszybicki.independentintelijaiplugin.logging

interface ModelToolRecorder {
    fun recordToolCall(record: ToolCallRecord)

    fun test(url: String): String

    fun close()
}
