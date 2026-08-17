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

/**
 * Reads [AgentConfiguration.FILE_NAME] from the project root -- or from wherever
 * [AgentConfiguration.PATH_ENV_VAR] points -- and turns a chat's chosen names back into the
 * configuration behind them.
 *
 * The file is read on every call rather than cached: it is a few hundred bytes, it is edited by hand
 * in the editor next to this, and a cache would mean an edit that appears to do nothing until the
 * IDE restarts. Which entry each conversation is on is held by the chat itself and saved with it;
 * [AICodingAgentSettingsState.State.activeConfiguration] holds only the default a new one starts on.
 *
 * Nothing here throws. A file that will not parse is reported as an error alongside an empty list,
 * and [resolve] falls back to [AgentConfiguration.fallback], so a typo in the JSON leaves the chat
 * usable while the settings page says what is wrong with it.
 */
@Service(Service.Level.PROJECT)
class AgentConfigurations(private val project: Project) {

    /** What one read of the file produced: its entries, and why there are none if there are none. */
    data class Loaded(val configurations: List<AgentConfiguration>, val error: String?)

    /**
     * What one read of the `usage-database` section produced: what it says, and why it says nothing
     * when it is there and unreadable.
     *
     * Separate from [Loaded] so the two cannot take each other down: a typo in the database section
     * must not cost the chat its providers, and an unparseable provider entry must not silently stop
     * the recording of the requests that still go out.
     */
    data class LoadedDatabase(val database: UsageDatabaseConfig, val error: String?)

    /**
     * Where the file is: what [AgentConfiguration.PATH_ENV_VAR] names, or the project root, or null
     * for a project with no directory on disk and no variable set.
     *
     * The variable wins over the project, and one variable serves every project the IDE has open --
     * which is the point of it. A path is read from the environment on every call rather than kept,
     * for the same reason the file's contents are: nothing here is expensive, and a cached answer
     * would mean a variable changed and the IDE restarted before it counted.
     */
    val path: Path?
        get() = configuredPath() ?: project.basePath?.let { Paths.get(it, AgentConfiguration.FILE_NAME) }

    /** Whether the location came from the environment rather than from the project. */
    val isExternal: Boolean
        get() = configuredPath() != null

    /**
     * Why the plugin must not start, or null when it may.
     *
     * The one fatal state there is: [AgentConfiguration.PATH_ENV_VAR] names a file that is not there.
     * Everything else here degrades -- a missing project file is written, an unparseable one falls
     * back to [AgentConfiguration.fallback] -- because a chat that still works while the settings
     * page says what is wrong beats no chat at all. This does not degrade, because the variable is a
     * deliberate instruction about which providers to use: falling back would send the conversation
     * somewhere the user has explicitly said not to look, and writing a starter file at that path
     * would invent one. So the tool window refuses to open with the path on it instead -- see
     * [com.github.piotrszybicki.independentintelijaiplugin.toolWindow.ChatToolWindowFactory] and
     * [AgentConfigurationStartup].
     */
    val unavailableReason: String?
        get() {
            val configured = configuredPath() ?: return null
            if (Files.exists(configured)) return null
            return "\$${AgentConfiguration.PATH_ENV_VAR} names $configured, which is not there."
        }

    /**
     * Writes the starter file if there is nothing there yet, and returns the file either way.
     *
     * Called from startup and from the settings page. Never overwrites: the file belongs to the
     * user from the moment it exists, and an entry deleted from it is meant to stay deleted.
     *
     * Writes nothing at all when [AgentConfiguration.PATH_ENV_VAR] is set. A variable that names a
     * file has been pointed at one that is meant to exist, so a path with a typo in it should be
     * reported as missing rather than quietly filled with three example providers -- and the path
     * can be anywhere, which is not somewhere to be creating files uninvited.
     */
    fun createIfMissing(): Path? {
        val file = path ?: return null
        if (Files.exists(file)) return file
        if (isExternal) {
            LOG.info("\$${AgentConfiguration.PATH_ENV_VAR} names $file, which is not there; not creating it")
            return null
        }
        val failure = save(AgentConfiguration.render(AgentConfiguration.STARTER, UsageDatabaseConfig.OFF))
        if (failure != null) return null
        LOG.info("wrote a starter configuration file to $file")
        return file
    }

    /** The file as a VFS file, creating it first, or null when it cannot be found or written. */
    fun virtualFile(): VirtualFile? =
        createIfMissing()?.let { LocalFileSystem.getInstance().refreshAndFindFileByIoFile(it.toFile()) }

    /**
     * Writes [configurations] back over the file, every optional field spelled out.
     *
     * What "fill in the defaults" is made of: an entry written before a field existed reads back
     * with that field's default, and this is what turns the default it is silently running on into
     * a line that can be seen and edited. Nothing is invented -- the values written are the ones
     * [load] just read, so what the file means is exactly what it meant before.
     *
     * Deliberately not automatic. It reformats a file the user owns and may be part-way through
     * editing, which is a thing to do when asked rather than on every project open.
     */
    fun rewrite(configurations: List<AgentConfiguration>, database: UsageDatabaseConfig): String? =
        save(AgentConfiguration.render(configurations, database))

    /**
     * Puts [text] in the file, through the VFS and inside a write action.
     *
     * Not `Files.writeString` and a refresh, which is the same thing only as long as nothing has the
     * file open: an editor holds a Document, and the highlighting passes hold data indexed by line
     * against it. Changing the bytes underneath that and refreshing afterwards leaves both to catch
     * up on their own, and what catches up second can be working from what the file used to be --
     * a stale line number is an IndexOutOfBoundsException from somewhere in the platform that names
     * nothing to do with this plugin. Writing through the VFS makes the document the thing that
     * changes, so everything watching it is told in the right order.
     *
     * Returns null on success, or the reason it could not be written.
     *
     * Refuses outright while [AgentConfiguration.PATH_ENV_VAR] is set. A file named by the
     * environment is the user's own, kept deliberately outside the project and quite possibly shared
     * by every project on the machine -- one project's idea of what belongs in it is not a reason to
     * rewrite it. The plugin reads that file and nothing else touches it.
     */
    private fun save(text: String): String? {
        configuredPath()?.let {
            return "$it is only read, never written, because \$${AgentConfiguration.PATH_ENV_VAR} " +
                "names it -- edit it yourself"
        }
        val basePath = project.basePath ?: return "this project has no directory on disk"
        return try {
            // Dispatches to the EDT and waits, so this is callable from startup and from the
            // settings page alike.
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

    /**
     * The file's text as it stands, or null when there is nothing readable there.
     *
     * The editor's copy wins over the bytes on disk when the file is open, which is the difference
     * between this and a plain read. The file is edited in the editor a few centimetres from the
     * dropdowns that display it, and the IDE only writes a document out when the whole frame loses
     * focus -- clicking a tool window is not that. Reading the disk would mean adding a model,
     * pressing refresh, and being shown the file as it was before the edit, with no clue why.
     */
    fun text(): String? {
        val file = path?.takeIf { Files.exists(it) } ?: return null
        val onDisk = runCatching { Files.readString(file) }.getOrNull()
        val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(file.toFile()) ?: return onDisk
        // getCachedDocument, not getDocument: nothing here is worth loading a document for a file
        // the user never opened, and a file never opened cannot be holding an edit the disk lacks.
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

    /**
     * Why there is nothing to read, said so the two places the file can be are told apart: a missing
     * project file is the ordinary first-run state, while a missing external one is a variable to fix
     * and has to name the path it was pointed at.
     */
    private fun notThere(): String =
        unavailableReason ?: "${AgentConfiguration.FILE_NAME} is not in the project root"

    /**
     * The `usage-database` section, or [UsageDatabaseConfig.OFF] and the reason when it cannot be
     * read.
     *
     * Read from the file on every call like everything else here, so switching the recording off or
     * pointing it at another server takes effect on the next request rather than on the next IDE
     * start. It is a few hundred bytes and the reader is off the request thread already.
     */
    fun usageDatabase(): LoadedDatabase {
        val file = path ?: return LoadedDatabase(UsageDatabaseConfig.OFF, null)
        // Missing is not an error here, unlike a missing set of configurations: a project that
        // records nothing never writes the section, and saying so once per request would be noise
        // about a feature that was never asked for.
        if (!Files.exists(file)) return LoadedDatabase(UsageDatabaseConfig.OFF, null)
        return try {
            LoadedDatabase(UsageDatabaseConfig.parse(text().orEmpty()), null)
        } catch (e: AgentConfigurationException) {
            LoadedDatabase(UsageDatabaseConfig.OFF, "${AgentConfiguration.FILE_NAME} is ${e.message}")
        } catch (e: Exception) {
            LoadedDatabase(UsageDatabaseConfig.OFF, "${AgentConfiguration.FILE_NAME} could not be read: ${e.message}")
        }
    }

    /**
     * The configuration requests go out with: the one named in the settings, the first in the file
     * when that name is not in it, and the built-in default when the file has nothing to offer.
     */
    /**
     * What a chat holding these two names sends to.
     *
     * Both are names rather than objects, and neither has to still exist: an entry that has been
     * renamed falls back to the first in the file, and a model that entry does not offer falls back
     * to its default. That is what lets a chat saved weeks ago be reopened against a file that has
     * moved on -- see [StoredChat][com.github.piotrszybicki.independentintelijaiplugin.history.StoredChat].
     */
    fun resolve(configurationName: String, modelName: String): AgentConfiguration {
        val configuration = select(load().configurations, configurationName)
            ?: AgentConfiguration.fallback()
        // Narrowed to the chosen model here rather than everywhere downstream, so nothing below has
        // to know that the model and the configuration are chosen in two different dropdowns.
        return configuration.withModel(modelName)
    }


    /**
     * What [AgentConfiguration.PATH_ENV_VAR] names, or null when it says nothing usable -- see
     * [AgentConfiguration.configuredPath], which is where the reading of it lives.
     *
     * A variable that is set and still resolves to nothing is logged here rather than there: it is a
     * path that cannot exist on this OS, so the project root is used and the log line is the only
     * place that says why.
     */
    private fun configuredPath(): Path? {
        val raw = System.getenv(AgentConfiguration.PATH_ENV_VAR)
        val resolved = AgentConfiguration.configuredPath(raw)
        if (resolved == null && !raw.isNullOrBlank() && warnedAbout != raw) {
            // Once per value: this is asked on every action update as well as on every read, and a
            // variable that cannot be a path stays one for the life of the IDE.
            warnedAbout = raw
            LOG.warn("\$${AgentConfiguration.PATH_ENV_VAR} is not a usable path: ${raw.trim()}")
        }
        return resolved
    }

    /** The unusable value already logged, so [configuredPath] says it once rather than per call. */
    @Volatile
    private var warnedAbout: String? = null

    companion object {
        private val LOG = Logger.getInstance(AgentConfigurations::class.java)

        fun getInstance(project: Project): AgentConfigurations = project.getService(AgentConfigurations::class.java)

        /**
         * Which of [configurations] the name [wanted] picks, or null when there are none to pick
         * from. A name that is not among them falls back to the first entry rather than to nothing:
         * the name is remembered application-wide while the file belongs to one project, so it can
         * legitimately be a name this project has never had.
         */
        fun select(configurations: List<AgentConfiguration>, wanted: String): AgentConfiguration? =
            configurations.firstOrNull { it.name == wanted } ?: configurations.firstOrNull()
    }
}
