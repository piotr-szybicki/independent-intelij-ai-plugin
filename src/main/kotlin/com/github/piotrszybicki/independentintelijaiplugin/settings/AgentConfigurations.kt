package com.github.piotrszybicki.independentintelijaiplugin.settings

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class AgentConfigurations :
    ConfigurationFile,
    ConfigurationSections,
    ConfigurationResolution {
    data class Loaded(
        val configurations: List<AgentConfiguration>,
        val error: String?,
    )

    data class LoadedDatabase(
        val database: UsageDatabaseConfig,
        val error: String?,
    )

    data class LoadedFindInFiles(
        val findInFiles: FindInFilesConfig,
        val error: String?,
    )

    data class LoadedAgents(
        val roster: AgentRosterConfig,
        val error: String?,
    )

    data class LoadedSummarizer(
        val summarizer: SummarizerConfig,
        val error: String?,
    )

    data class LoadedConversationDefaults(
        val defaults: ConversationDefaultsConfig,
        val error: String?,
    )

    val path: Path?
        get() = configuredPath()

    val isReadOnly: Boolean
        get() = true

    val unavailableReason: String?
        get() {
            val raw = System.getenv(AgentConfiguration.PATH_ENV_VAR)
            val setupInstructions = AgentConfiguration.SETUP_INSTRUCTIONS
            AgentConfiguration.environmentProblem(raw)?.let { return "$it $setupInstructions" }

            val configured = configuredPath() ?: return setupInstructions
            if (!Files.isRegularFile(configured) || !Files.isReadable(configured)) {
                return "\$${AgentConfiguration.PATH_ENV_VAR} names $configured, " +
                    "which is not a readable regular file. " +
                    setupInstructions
            }
            return configurationError()?.let { "$it $setupInstructions" }
        }

    override fun createIfMissing(): Path? {
        val file = path ?: return null
        return file.takeIf { Files.isRegularFile(it) }
    }

    override fun virtualFile(): VirtualFile? =
        createIfMissing()?.let { LocalFileSystem.getInstance().refreshAndFindFileByIoFile(it.toFile()) }

    override fun rewrite(
        configurations: List<AgentConfiguration>,
        database: UsageDatabaseConfig,
        findInFiles: FindInFilesConfig,
        agents: AgentRosterConfig,
        summarizer: SummarizerConfig,
        conversationDefaults: ConversationDefaultsConfig,
    ): String? =
        "Configuration is read from \$${AgentConfiguration.PATH_ENV_VAR} only and is never written by the plugin."

    override fun text(): String? {
        val file = path?.takeIf { Files.exists(it) } ?: return null
        val onDisk = runCatching { Files.readString(file) }.getOrNull()
        val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(file.toFile()) ?: return onDisk
        return ReadAction.computeBlocking<String?, RuntimeException> {
            FileDocumentManager.getInstance().getCachedDocument(virtualFile)?.text
        } ?: onDisk
    }

    override fun load(): Loaded {
        val file = path ?: return Loaded(emptyList(), unavailableReason ?: "A configuration file is required.")
        if (!Files.exists(file)) return Loaded(emptyList(), notThere())
        return try {
            val configurations = AgentConfiguration.parseAll(text().orEmpty())
            if (configurations.isEmpty()) {
                Loaded(emptyList(), "${AgentConfiguration.FILE_NAME} has no configurations in it")
            } else {
                Loaded(configurations, null)
            }
        } catch (e: AgentConfigurationException) {
            Loaded(emptyList(), "${AgentConfiguration.FILE_NAME} is ${e.message}")
        } catch (e: Exception) {
            Loaded(emptyList(), "${AgentConfiguration.FILE_NAME} could not be read: ${e.message}")
        }
    }

    private fun notThere(): String =
        path?.let { "\$${AgentConfiguration.PATH_ENV_VAR} names $it, which is not there." }
            ?: (
                AgentConfiguration.environmentProblem(System.getenv(AgentConfiguration.PATH_ENV_VAR))
                    ?: AgentConfiguration.SETUP_INSTRUCTIONS
            )

    private fun configurationError(): String? =
        listOf(
            load().error,
            usageDatabase().error,
            findInFiles().error,
            agents().error,
            summarizer().error,
            conversationDefaults().error,
        ).firstOrNull { it != null }

    override fun usageDatabase(): LoadedDatabase {
        val file = path ?: return LoadedDatabase(UsageDatabaseConfig.OFF, null)
        if (!Files.exists(file)) return LoadedDatabase(UsageDatabaseConfig.OFF, null)
        return try {
            LoadedDatabase(UsageDatabaseConfig.parse(text().orEmpty()), null)
        } catch (e: AgentConfigurationException) {
            LoadedDatabase(UsageDatabaseConfig.OFF, "${AgentConfiguration.FILE_NAME} is ${e.message}")
        } catch (e: Exception) {
            LoadedDatabase(UsageDatabaseConfig.OFF, "${AgentConfiguration.FILE_NAME} could not be read: ${e.message}")
        }
    }

    override fun findInFiles(): LoadedFindInFiles {
        val file = path ?: return LoadedFindInFiles(FindInFilesConfig.DEFAULT, null)
        if (!Files.exists(file)) return LoadedFindInFiles(FindInFilesConfig.DEFAULT, null)
        return try {
            LoadedFindInFiles(FindInFilesConfig.parse(text().orEmpty()), null)
        } catch (e: AgentConfigurationException) {
            LoadedFindInFiles(FindInFilesConfig.DEFAULT, "${AgentConfiguration.FILE_NAME} is ${e.message}")
        } catch (e: Exception) {
            LoadedFindInFiles(
                FindInFilesConfig.DEFAULT,
                "${AgentConfiguration.FILE_NAME} could not be read: ${e.message}",
            )
        }
    }

    override fun agents(): LoadedAgents {
        val file = path ?: return LoadedAgents(AgentRosterConfig.EMPTY, null)
        if (!Files.exists(file)) return LoadedAgents(AgentRosterConfig.EMPTY, null)
        return try {
            LoadedAgents(AgentRosterConfig.parse(text().orEmpty()), null)
        } catch (e: AgentConfigurationException) {
            LoadedAgents(AgentRosterConfig.EMPTY, "${AgentConfiguration.FILE_NAME} is ${e.message}")
        } catch (e: Exception) {
            LoadedAgents(
                AgentRosterConfig.EMPTY,
                "${AgentConfiguration.FILE_NAME} could not be read: ${e.message}",
            )
        }
    }

    override fun summarizer(): LoadedSummarizer {
        val file = path ?: return LoadedSummarizer(SummarizerConfig.DEFAULT, null)
        if (!Files.exists(file)) return LoadedSummarizer(SummarizerConfig.DEFAULT, null)
        return try {
            LoadedSummarizer(SummarizerConfig.parse(text().orEmpty()), null)
        } catch (e: AgentConfigurationException) {
            LoadedSummarizer(SummarizerConfig.DEFAULT, "${AgentConfiguration.FILE_NAME} is ${e.message}")
        } catch (e: Exception) {
            LoadedSummarizer(
                SummarizerConfig.DEFAULT,
                "${AgentConfiguration.FILE_NAME} could not be read: ${e.message}",
            )
        }
    }

    override fun conversationDefaults(): LoadedConversationDefaults {
        val file = path ?: return LoadedConversationDefaults(ConversationDefaultsConfig.DEFAULT, null)
        if (!Files.exists(file)) return LoadedConversationDefaults(ConversationDefaultsConfig.DEFAULT, null)
        return try {
            LoadedConversationDefaults(ConversationDefaultsConfig.parse(text().orEmpty()), null)
        } catch (e: AgentConfigurationException) {
            LoadedConversationDefaults(
                ConversationDefaultsConfig.DEFAULT,
                "${AgentConfiguration.FILE_NAME} is ${e.message}",
            )
        } catch (e: Exception) {
            LoadedConversationDefaults(
                ConversationDefaultsConfig.DEFAULT,
                "${AgentConfiguration.FILE_NAME} could not be read: ${e.message}",
            )
        }
    }

    override fun resolve(
        configurationName: String,
        modelName: String,
    ): AgentConfiguration {
        val loaded = load()
        val configuration =
            select(loaded.configurations, configurationName)
                ?: error(loaded.error ?: "No configuration is available.")
        return configuration.withModel(modelName)
    }

    private fun configuredPath(): Path? {
        val raw = System.getenv(AgentConfiguration.PATH_ENV_VAR)
        val resolved = AgentConfiguration.configuredPath(raw)
        if (resolved == null && !raw.isNullOrBlank() && warnedAbout != raw) {
            warnedAbout = raw
            LOG.warn("\$${AgentConfiguration.PATH_ENV_VAR} is not a usable path: ${raw.trim()}")
        }
        return resolved
    }

    @Volatile
    private var warnedAbout: String? = null

    companion object {
        private val LOG = Logger.getInstance(AgentConfigurations::class.java)

        fun getInstance(project: Project): AgentConfigurations = project.getService(AgentConfigurations::class.java)

        fun select(
            configurations: List<AgentConfiguration>,
            wanted: String,
        ): AgentConfiguration? = configurations.firstOrNull { it.name == wanted } ?: configurations.firstOrNull()
    }
}
