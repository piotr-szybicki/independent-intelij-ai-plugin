package com.github.piotrszybicki.independentintelijaiplugin.settings

interface ConfigurationResolution {
    fun summarizer(): AgentConfigurations.LoadedSummarizer

    fun conversationDefaults(): AgentConfigurations.LoadedConversationDefaults

    fun resolve(
        configurationName: String,
        modelName: String,
    ): AgentConfiguration
}
