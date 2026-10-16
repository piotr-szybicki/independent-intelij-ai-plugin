package com.github.piotrszybicki.independentintelijaiplugin.settings

import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

interface ConfigurationFile {
    fun createIfMissing(): Path?

    fun virtualFile(): VirtualFile?

    fun rewrite(
        configurations: List<AgentConfiguration>,
        database: UsageDatabaseConfig,
        findInFiles: FindInFilesConfig,
        agents: AgentRosterConfig,
        summarizer: SummarizerConfig,
        conversationDefaults: ConversationDefaultsConfig,
    ): String?

    fun text(): String?
}
