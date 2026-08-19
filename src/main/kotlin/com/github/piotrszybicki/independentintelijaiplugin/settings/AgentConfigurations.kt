package com.github.piotrszybicki.independentintelijaiplugin.settings

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

@Service(Service.Level.PROJECT)
class AgentConfigurations(private val project: Project) {

    data class Loaded(val configurations: List<AgentConfiguration>, val error: String?)

    data class LoadedDatabase(val database: UsageDatabaseConfig, val error: String?)

    data class LoadedFindInFiles(val findInFiles: FindInFilesConfig, val error: String?)

    data class LoadedAgents(val roster: AgentRosterConfig, val error: String?)

    val path: Path?
        get() = configuredPath() ?: project.basePath?.let { Paths.get(it, AgentConfiguration.FILE_NAME) }

    val isExternal: Boolean
        get() = configuredPath() != null

    val unavailableReason: String?
        get() {
            val configured = configuredPath() ?: return null
            if (Files.exists(configured)) return null
            return "\$${AgentConfiguration.PATH_ENV_VAR} names $configured, which is not there."
        }

    fun createIfMissing(): Path? {
        val file = path ?: return null
        if (Files.exists(file)) return file
        if (isExternal) {
            LOG.info("\$${AgentConfiguration.PATH_ENV_VAR} names $file, which is not there; not creating it")
            return null
        }
        val failure = save(
            AgentConfiguration.render(
                AgentConfiguration.STARTER,
                UsageDatabaseConfig.OFF,
                FindInFilesConfig.DEFAULT,
            ),
        )
        if (failure != null) return null
        LOG.info("wrote a starter configuration file to $file")
        return file
    }

    fun virtualFile(): VirtualFile? =
        createIfMissing()?.let { LocalFileSystem.getInstance().refreshAndFindFileByIoFile(it.toFile()) }

    fun rewrite(
        configurations: List<AgentConfiguration>,
        database: UsageDatabaseConfig,
        findInFiles: FindInFilesConfig,
        agents: AgentRosterConfig,
    ): String? = save(AgentConfiguration.render(configurations, database, findInFiles, agents))

    private fun save(text: String): String? {
        configuredPath()?.let {
            return "$it is only read, never written, because \$${AgentConfiguration.PATH_ENV_VAR} " +
                "names it -- edit it yourself"
        }
        val basePath = project.basePath ?: return "this project has no directory on disk"
        return try {
            WriteAction.runAndWait<IOException> {
                val directory = LocalFileSystem.getInstance().refreshAndFindFileByPath(basePath)
                    ?: throw IOException("the project directory $basePath is not on disk")
                val file = directory.findChild(AgentConfiguration.FILE_NAME)
                    ?: directory.createChildData(this, AgentConfiguration.FILE_NAME)
                VfsUtil.saveText(file, text)
            }
            null
        } catch (e: IOException) {
            LOG.warn("could not write ${AgentConfiguration.FILE_NAME} in $basePath", e)
            "${AgentConfiguration.FILE_NAME} could not be written: ${e.message}"
        }
    }

    fun text(): String? {
        val file = path?.takeIf { Files.exists(it) } ?: return null
        val onDisk = runCatching { Files.readString(file) }.getOrNull()
        val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(file.toFile()) ?: return onDisk
        return ReadAction.computeBlocking<String?, RuntimeException> {
            FileDocumentManager.getInstance().getCachedDocument(virtualFile)?.text
        } ?: onDisk
    }

    fun load(): Loaded {
        val file = path ?: return Loaded(emptyList(), "this project has no directory on disk")
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
        unavailableReason ?: "${AgentConfiguration.FILE_NAME} is not in the project root"

    fun usageDatabase(): LoadedDatabase {
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

    fun findInFiles(): LoadedFindInFiles {
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

    fun agents(): LoadedAgents {
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

    fun resolve(configurationName: String, modelName: String): AgentConfiguration {
        val configuration = select(load().configurations, configurationName)
            ?: AgentConfiguration.fallback()
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

        fun select(configurations: List<AgentConfiguration>, wanted: String): AgentConfiguration? =
            configurations.firstOrNull { it.name == wanted } ?: configurations.firstOrNull()
    }
}
