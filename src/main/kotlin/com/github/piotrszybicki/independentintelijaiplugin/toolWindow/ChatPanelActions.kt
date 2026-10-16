package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.intellij.openapi.actionSystem.AnActionEvent

internal interface ChatPanelActions {
    fun refreshProviderBar()

    fun restoreLastChatNow()

    fun startNewChat()

    fun showHistory(event: AnActionEvent)
}
