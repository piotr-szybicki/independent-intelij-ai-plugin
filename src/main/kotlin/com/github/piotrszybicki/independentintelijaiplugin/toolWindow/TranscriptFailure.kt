package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

internal interface TranscriptFailure {
    fun markRequestFailed(onRetry: () -> Unit)

    fun clearRequestFailure()

    fun addError(message: String)
}
