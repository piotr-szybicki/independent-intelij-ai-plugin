package com.github.piotrszybicki.independentintelijaiplugin.history

internal object ChatHistoryHierarchy {
    fun levelOf(
        chatId: String,
        chats: List<ChatSummary>,
    ): Int? {
        val byId = chats.associateBy(ChatSummary::id)
        var current = byId[chatId] ?: return null
        val visited = mutableSetOf<String>()
        var level = 1

        while (true) {
            if (!visited.add(current.id)) return null
            val parentId = current.parentChatId ?: return level
            current = byId[parentId] ?: return null
            level++
        }
    }

    fun arrange(chats: List<ChatSummary>): List<ChatHistoryEntry> {
        val byId = chats.associateBy(ChatSummary::id)
        val children =
            chats
                .filter { chat -> chat.parentChatId?.let { it != chat.id && byId.containsKey(it) } == true }
                .groupBy { it.parentChatId }
        val activity = mutableMapOf<String, Long>()

        fun latestActivity(
            chat: ChatSummary,
            ancestors: Set<String> = emptySet(),
        ): Long {
            activity[chat.id]?.let { return it }
            if (chat.id in ancestors) return chat.updatedAt
            val latest =
                children[chat.id]
                    .orEmpty()
                    .maxOfOrNull { latestActivity(it, ancestors + chat.id) }
                    ?.coerceAtLeast(chat.updatedAt)
                    ?: chat.updatedAt
            activity[chat.id] = latest
            return latest
        }

        val result = mutableListOf<ChatHistoryEntry>()
        val visited = mutableSetOf<String>()

        fun append(
            chat: ChatSummary,
            depth: Int,
        ) {
            if (!visited.add(chat.id)) return
            result += ChatHistoryEntry(chat, depth)
            children[chat.id]
                .orEmpty()
                .sortedByDescending { latestActivity(it) }
                .forEach { append(it, depth + 1) }
        }

        chats
            .filter { it.parentChatId == null || it.parentChatId !in byId || it.parentChatId == it.id }
            .sortedByDescending { latestActivity(it) }
            .forEach { append(it, 0) }

        chats
            .filterNot { it.id in visited }
            .sortedByDescending { latestActivity(it) }
            .forEach { append(it, 0) }

        return result
    }
}
