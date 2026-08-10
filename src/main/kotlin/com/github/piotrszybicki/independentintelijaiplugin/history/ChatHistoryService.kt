package com.github.piotrszybicki.independentintelijaiplugin.history

import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.ChatMessage
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.SessionUsage
import com.google.gson.Gson
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * One row as the transcript drew it.
 *
 * Stored alongside the API messages rather than derived from them, because neither one can be
 * rebuilt from the other: an attached file is sent in full but shown as a chip, and a tool call
 * carries ids and result blocks the transcript never renders.
 */
data class StoredRow(
    val kind: String,
    val text: String = "",
    val name: String = "",
    val summary: String = "",
    val details: String = "",
    /**
     * How a [TOOL] row ended, or null for one that ended well -- which is also what chats saved
     * before this field existed come back as, since a tool call that was written to disk at all had
     * already run.
     */
    val status: String? = null,
) {
    companion object {
        const val USER = "user"
        const val ASSISTANT = "assistant"
        const val TOOL = "tool"
        const val ERROR = "error"

        /** Values for [status]. Success is the absent one. */
        const val FAILED = "failed"
        const val CANCELLED = "cancelled"
    }
}

data class StoredChat(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** The conversation as the API sees it -- assistant turns, `tool_use` and `tool_result` included. */
    val messages: List<ChatMessage>,
    /** The conversation as the tool window drew it. */
    val transcript: List<StoredRow>,
    /**
     * What this chat has spent so far, or null for one saved before the counter existed -- there is
     * no recovering it after the fact, so those come back at zero and count on from there.
     */
    val usage: SessionUsage? = null,
)

/** What the history popup lists, so opening it does not have to parse every transcript. */
data class ChatSummary(val id: String, val title: String, val updatedAt: Long)

/**
 * Saved conversations for one project.
 *
 * Transcripts are written as JSON files under the IDE's system directory rather than into a
 * settings XML or the project's `.idea` folder: a single chat can carry whole files' worth of tool
 * output, it is personal rather than shared, and it has no business in version control.
 *
 * `index.json` next to them holds just the titles and timestamps the popup needs, plus which chat
 * the tool window should come back to. It is a cache, not the source of truth -- if it is missing or
 * unreadable it is rebuilt by reading the transcripts themselves, so losing it costs a moment rather
 * than the history.
 *
 * Every method touches the disk, so call them off the EDT unless the write has to happen before the
 * caller returns (closing the tool window).
 */
@Service(Service.Level.PROJECT)
class ChatHistoryService(project: Project) {

    private val log = Logger.getInstance(ChatHistoryService::class.java)
    private val gson = Gson()
    private val lock = Any()

    private val directory: Path =
        Paths.get(PathManager.getSystemPath(), "aiCodingAgentChat", "chats", project.locationHash)

    private data class Index(var activeId: String?, val chats: MutableList<ChatSummary>)

    /** Newest first. */
    fun chats(): List<ChatSummary> = synchronized(lock) { readIndex().chats.toList() }

    /** The chat the tool window was last left on, if it is still around. */
    fun activeId(): String? = synchronized(lock) { readIndex().activeId }

    fun setActiveId(id: String?) {
        synchronized(lock) {
            runCatching {
                val index = readIndex()
                index.activeId = id
                writeIndex(index)
            }.onFailure { log.warn("Could not record the active chat: ${it.message}") }
        }
    }

    fun load(id: String): StoredChat? = synchronized(lock) {
        runCatching {
            gson.fromJson(Files.readString(fileFor(id)), StoredChat::class.java)
                // Gson fills absent fields with null whatever the Kotlin type says, so a file
                // written by a different version of these classes -- or one that never finished
                // being written -- can parse into an object with holes in it. Reading every field
                // that matters here turns that into a failed load rather than a crash later on.
                .takeIf { it.id.isNotEmpty() && it.messages.isNotEmpty() && it.transcript.isNotEmpty() }
        }.onFailure { log.warn("Could not read chat $id: ${it.message}") }.getOrNull()
    }

    /**
     * Writes [chat] out, replacing any earlier version of it.
     *
     * [active] says whether this is the chat the tool window is still sitting on. Saving one the
     * user is leaving passes false, which also drops it as the active chat if it was -- otherwise
     * reopening the IDE would come back to a conversation that has been navigated away from.
     */
    fun save(chat: StoredChat, active: Boolean = true) {
        if (chat.messages.isEmpty() || chat.transcript.isEmpty()) return
        synchronized(lock) {
            runCatching {
                Files.createDirectories(directory)
                writeAtomically(fileFor(chat.id), gson.toJson(chat))

                val index = readIndex()
                index.chats.removeAll { it.id == chat.id }
                index.chats.add(ChatSummary(chat.id, chat.title, chat.updatedAt))
                index.chats.sortByDescending { it.updatedAt }
                index.activeId = if (active) chat.id else index.activeId?.takeIf { it != chat.id }
                prune(index)
                writeIndex(index)
            }.onFailure { log.warn("Could not save chat ${chat.id}: ${it.message}") }
        }
    }

    fun delete(id: String) {
        synchronized(lock) {
            runCatching {
                Files.deleteIfExists(fileFor(id))
                val index = readIndex()
                index.chats.removeAll { it.id == id }
                if (index.activeId == id) index.activeId = null
                writeIndex(index)
            }.onFailure { log.warn("Could not delete chat $id: ${it.message}") }
        }
    }

    // --- storage --------------------------------------------------------------------------------

    private fun fileFor(id: String): Path = directory.resolve("$id.json")

    private fun readIndex(): Index {
        val parsed = runCatching { gson.fromJson(Files.readString(directory.resolve(INDEX_FILE)), Index::class.java) }
            .getOrNull()
        // Null covers all of "no file yet", "unreadable", and "parsed but missing its chat list".
        val chats = parsed?.chats ?: return rebuildIndex()
        return Index(parsed.activeId, chats)
    }

    /** Reads the transcripts back to work out what the index should have said. */
    private fun rebuildIndex(): Index {
        val summaries = chatIdsOnDisk()
            .mapNotNull { id -> load(id)?.let { ChatSummary(it.id, it.title, it.updatedAt) } }
            .sortedByDescending { it.updatedAt }

        // The chat the window was last on is not recoverable this way, so the next open starts
        // fresh rather than guessing at it.
        val index = Index(null, summaries.toMutableList())
        if (summaries.isNotEmpty()) runCatching { writeIndex(index) }
        return index
    }

    private fun chatIdsOnDisk(): List<String> = runCatching {
        Files.newDirectoryStream(directory, "*.json").use { stream ->
            stream.map { it.fileName.toString().removeSuffix(".json") }.filter { it != INDEX_ID }
        }
    }.getOrDefault(emptyList())

    private fun writeIndex(index: Index) {
        Files.createDirectories(directory)
        writeAtomically(directory.resolve(INDEX_FILE), gson.toJson(index))
    }

    /** Drops the oldest chats once there are more than [MAX_CHATS]. [index] is newest first. */
    private fun prune(index: Index) {
        while (index.chats.size > MAX_CHATS) {
            val oldest = index.chats.removeAt(index.chats.lastIndex)
            runCatching { Files.deleteIfExists(fileFor(oldest.id)) }
        }
    }

    /**
     * Writes through a temporary file, so a crash or a full disk mid-write leaves the previous
     * version intact instead of a half-written one that will not parse.
     */
    private fun writeAtomically(target: Path, text: String) {
        val temp = target.resolveSibling("${target.fileName}.tmp")
        Files.writeString(temp, text)
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        private const val INDEX_ID = "index"
        private const val INDEX_FILE = "$INDEX_ID.json"

        /** Old chats are dropped rather than kept forever; each one can be megabytes of tool output. */
        private const val MAX_CHATS = 50

        private const val UNTITLED = "New chat"
        private const val MAX_TITLE_LENGTH = 60

        fun getInstance(project: Project): ChatHistoryService = project.getService(ChatHistoryService::class.java)

        /** On the companion so naming a chat never has to instantiate the service. */
        fun newChatId(): String = UUID.randomUUID().toString()

        /** Names a chat after what the user opened it with -- its first line, trimmed of markup. */
        fun titleFor(transcript: List<StoredRow>): String {
            val opening = transcript.firstOrNull { it.kind == StoredRow.USER }?.text.orEmpty()
            val line = opening.lineSequence()
                .map { it.trim().trimStart('#', '>', '-', '*', ' ').replace('`', ' ').trim() }
                .firstOrNull { it.isNotEmpty() }
                ?: return UNTITLED
            return if (line.length > MAX_TITLE_LENGTH) line.take(MAX_TITLE_LENGTH - 1).trimEnd() + "…" else line
        }
    }
}
