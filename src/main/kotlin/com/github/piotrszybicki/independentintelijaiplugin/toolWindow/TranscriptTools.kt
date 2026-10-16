package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

internal interface TranscriptTools {
    fun addToolCall(
        requestId: String,
        name: String,
        summary: String,
        details: String,
        status: ChatTranscript.ToolStatus = ChatTranscript.ToolStatus.DONE,
    ): ChatTranscript.RunningTool

    fun startToolCall(
        requestId: String,
        name: String,
        summary: String,
        details: String,
    ): ChatTranscript.RunningTool

    fun endAiTurn()

    fun setTurnContinuable(offered: Boolean)
}
