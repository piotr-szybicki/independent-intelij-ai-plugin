package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

internal interface AttachmentEditor {
    fun attachFromEditor()

    fun startSideChat(
        code: String,
        displayPath: String,
        filePath: String?,
        extension: String,
        lineRange: String,
    )

    fun add(
        body: String,
        summary: String,
        filePath: String?,
    )

    fun clear()
}
