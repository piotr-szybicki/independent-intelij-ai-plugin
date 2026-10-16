package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

internal interface FailedRequest {
    fun markFailed(action: () -> Unit)

    fun clearFailure()
}
