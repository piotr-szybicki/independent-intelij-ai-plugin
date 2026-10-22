package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

internal interface RestoreCheckpointMessages {
    fun addRestoreCheckpoint(onRestore: () -> Unit)
}
