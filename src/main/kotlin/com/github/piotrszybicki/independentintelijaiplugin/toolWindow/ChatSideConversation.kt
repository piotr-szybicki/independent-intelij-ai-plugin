package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

internal interface ChatSideConversation {
    fun startSideChat(
        code: String,
        displayPath: String,
        extension: String,
        lineRange: String,
    )
}
