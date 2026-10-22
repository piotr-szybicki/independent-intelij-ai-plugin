package com.github.piotrszybicki.independentintelijaiplugin.ui

data class RequiredControl(
    val id: String,
    val description: String,
    val requiredInBaseline: Boolean = true,
)

val mainChatControls =
    listOf(
        RequiredControl("chat.root", "Main chat root"),
        RequiredControl("chat.providerCombo", "Provider selector"),
        RequiredControl("chat.modelCombo", "Model selector"),
        RequiredControl("chat.conversationToolsButton", "Conversation tools"),
        RequiredControl("chat.promptInput", "Prompt input"),
        RequiredControl("chat.attachButton", "Attach button"),
        RequiredControl("chat.sendButton", "Send button"),
        RequiredControl("chat.statusLabel", "Status label"),
        RequiredControl("chat.usageLabel", "Usage label"),
        RequiredControl("chat.contextLabel", "Context label"),
        RequiredControl("chat.transcript", "Transcript"),
        RequiredControl("chat.composer", "Composer"),
    )
