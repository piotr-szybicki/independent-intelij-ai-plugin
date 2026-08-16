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
 * Reads [AgentConfiguration.FILE_NAME] from the project root and turns a chat's chosen names back
 * into the configuration behind them.
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

    /** Where the file is, or null for a project with no directory on disk. */
    val path: Path?
        get() = project.basePath?.let { Paths.get(it, AgentConfiguration.FILE_NAME) }

    /**
     * Writes the starter file if there is nothing there yet, and returns the file either way.
     *
     * Called from startup and from the settings page. Never overwrites: the file belongs to the
     * user from the moment it exists, and an entry deleted from it is meant to stay deleted.
     */
    fun createIfMissing(): Path? {
        val file = path ?: return null
        if (Files.exists(file)) return file
        val failure = save(AgentConfiguration.render(AgentConfiguration.STARTER))
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
    fun rewrite(configurations: List<AgentConfiguration>): String? =
        save(AgentConfiguration.render(configurations))

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
     */
    private fun save(text: String): String? {
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
        return ReadAction.compute<String?, RuntimeException> {
            FileDocumentManager.getInstance().getCachedDocument(virtualFile)?.text
        } ?: onDisk
    }

    fun load(): Loaded {
        val file = path ?: return Loaded(emptyList(), "this project has no directory on disk")
        if (!Files.exists(file)) {
            return Loaded(emptyList(), "${AgentConfiguration.FILE_NAME} is not in the project root")
        }
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
