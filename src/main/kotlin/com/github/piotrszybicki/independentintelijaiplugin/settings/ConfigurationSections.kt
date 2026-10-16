package com.github.piotrszybicki.independentintelijaiplugin.settings

interface ConfigurationSections {
    fun load(): AgentConfigurations.Loaded

    fun usageDatabase(): AgentConfigurations.LoadedDatabase

    fun findInFiles(): AgentConfigurations.LoadedFindInFiles

    fun agents(): AgentConfigurations.LoadedAgents
}
