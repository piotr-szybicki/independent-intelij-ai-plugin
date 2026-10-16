package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

internal interface TranscriptStatus {
    fun setTurnCost(
        text: String?,
        tooltip: String? = null,
    )

    fun setCancellable(cancellable: Boolean)

    fun setThinking(thinking: Boolean)

    fun clear()
}
