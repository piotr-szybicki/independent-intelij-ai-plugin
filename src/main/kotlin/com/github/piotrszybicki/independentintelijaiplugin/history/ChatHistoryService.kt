package com.github.piotrszybicki.independentintelijaiplugin.history

import com.github.piotrszybicki.independentintelijaiplugin.agents.AgentHandoff
import com.github.piotrszybicki.independentintelijaiplugin.agents.AgentReturn
import com.github.piotrszybicki.independentintelijaiplugin.agents.AgentSession
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.ChatMessage
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.ContextMeter
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.SessionUsage
import com.github.piotrszybicki.independentintelijaiplugin.settings.ConversationTools
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

data class StoredRow(
    val kind: String,
    val text: String = "",
    val name: String = "",
    val summary: String = "",
    val details: String = "",
    val status: String? = null,

    val requestId: String? = null,

    val handoff: AgentHandoff? = null,
) {
    companion object {
        const val USER = "user"
        const val ASSISTANT = "assistant"
        const val TOOL = "tool"
        const val ERROR = "error"

        const val HANDOFF = "handoff"

        const val THINKING = "thinking"

        const val COST = "cost"

        const val FAILED = "failed"
        const val CANCELLED = "cancelled"
    }
}

data class StoredChat(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<ChatMessage>,
    val transcript: List<StoredRow>,
    val usage: SessionUsage? = null,

    val context: ContextMeter.State? = null,

    val configurationName: String? = null,
    val model: String? = null,

    val agent: AgentSession? = null,

    val returns: List<AgentReturn>? = null,

    val conversationTools: ConversationTools? = null,
)

data class ChatSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,

    val agentName: String? = null,
)

@Service(Service.Level.PROJECT)
class ChatHistoryService(project: Project) {

    private val log = Logger.getInstance(ChatHistoryService::class.java)
    private val gson = Gson()
    private val lock = Any()

    private val directory: Path =
        Paths.get(PathManager.getSystemPath(), "aiCodingAgentChat", "chats", project.locationHash)

    private data class Index(var activeId: String?, val chats: MutableList<ChatSummary>)

    fun chats(): List<ChatSummary> = synchronized(lock) { readIndex().chats.toList() }

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
                .takeIf { it.id.isNotEmpty() && it.messages.isNotEmpty() && it.transcript.isNotEmpty() }
        }.onFailure { log.warn("Could not read chat $id: ${it.message}") }.getOrNull()
    }

    fun save(chat: StoredChat, active: Boolean = true) {
        if (chat.messages.isEmpty() || chat.transcript.isEmpty()) return
        synchronized(lock) {
            runCatching {
                Files.createDirectories(directory)
                writeAtomically(fileFor(chat.id), gson.toJson(chat))

                val index = readIndex()
                index.chats.removeAll { it.id == chat.id }
                index.chats.add(ChatSummary(chat.id, chat.title, chat.updatedAt, chat.agent?.agentName))
                index.chats.sortByDescending { it.updatedAt }
                index.activeId = if (active) chat.id else index.activeId?.takeIf { it != chat.id }
                prune(index)
                writeIndex(index)
            }.onFailure { log.warn("Could not save chat ${chat.id}: ${it.message}") }
        }
    }

    fun addReturn(chatId: String, returned: AgentReturn): Boolean {
        synchronized(lock) {
            val chat = load(chatId) ?: return false
            save(
                chat.copy(returns = chat.returns.orEmpty() + returned, updatedAt = System.currentTimeMillis()),
                active = readIndex().activeId == chatId,
            )
            return true
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

    fun deleteAll() {
        synchronized(lock) {
            runCatching {
                chatIdsOnDisk().forEach { id -> Files.deleteIfExists(fileFor(id)) }
                writeIndex(Index(null, mutableListOf()))
            }.onFailure { log.warn("Could not delete the chat history: ${it.message}") }
        }
    }

    private fun fileFor(id: String): Path = directory.resolve("$id.json")

    private fun readIndex(): Index {
        val parsed = runCatching { gson.fromJson(Files.readString(directory.resolve(INDEX_FILE)), Index::class.java) }
            .getOrNull()
        val chats = parsed?.chats ?: return rebuildIndex()
        return Index(parsed.activeId, chats)
    }

    private fun rebuildIndex(): Index {
        val summaries = chatIdsOnDisk()
            .mapNotNull { id -> load(id)?.let { ChatSummary(it.id, it.title, it.updatedAt, it.agent?.agentName) } }
            .sortedByDescending { it.updatedAt }

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

    private fun prune(index: Index) {
        while (index.chats.size > MAX_CHATS) {
            val oldest = index.chats.removeAt(index.chats.lastIndex)
            runCatching { Files.deleteIfExists(fileFor(oldest.id)) }
        }
    }

    private fun writeAtomically(target: Path, text: String) {
        val temp = target.resolveSibling("${target.fileName}.tmp")
        Files.writeString(temp, text)
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        private const val INDEX_ID = "index"
        private const val INDEX_FILE = "$INDEX_ID.json"

        private const val MAX_CHATS = 50

        private const val UNTITLED = "New chat"
        private const val MAX_TITLE_LENGTH = 60

        fun getInstance(project: Project): ChatHistoryService = project.getService(ChatHistoryService::class.java)

        fun newChatId(): String = UUID.randomUUID().toString()

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
