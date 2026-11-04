package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.intellij.build.events.BuildEvent
import com.intellij.build.events.FileMessageEvent
import com.intellij.build.events.MessageEvent
import com.intellij.build.events.StartBuildEvent

/** Collects errors only from the build session started by this tool. */
internal class RebuildDiagnostics(
    private val sessionId: Any,
    private val trackUnmatchedBuilds: Boolean = true,
) {
    private val messages = linkedSetOf<String>()
    private var omitted = 0
    private var size = 0
    private var unmatchedId: Any? = null
    private var unmatched: RebuildDiagnostics? = null
    private var ambiguous = false

    @Synchronized
    fun accept(
        buildId: Any,
        event: BuildEvent,
    ) {
        if (buildId != sessionId) {
            if (!trackUnmatchedBuilds) return
            if (event is StartBuildEvent) {
                if (unmatchedId == null) {
                    unmatchedId = buildId
                    unmatched = RebuildDiagnostics(buildId, false)
                } else if (buildId != unmatchedId) {
                    ambiguous = true
                }
            }
            if (buildId == unmatchedId) unmatched?.accept(buildId, event)
            return
        }
        if (event !is MessageEvent || event.kind != MessageEvent.Kind.ERROR) return

        val location =
            (event as? FileMessageEvent)
                ?.filePosition
                ?.let { position ->
                    buildString {
                        append(position.path)
                        if (position.startLine >= 0) append(":${position.startLine + 1}")
                        if (position.startColumn >= 0) append(":${position.startColumn + 1}")
                        append(": ")
                    }
                }.orEmpty()
        val title = event.message.trim()
        val detail = event.description?.trim().orEmpty()
        val text = if (detail.isNotEmpty() && detail != title) "$title\n$detail" else title
        val rawMessage = location + text
        val message =
            if (rawMessage.length > MAX_CHARS) {
                rawMessage.take(MAX_CHARS - 30) + "\n[Error message truncated.]"
            } else {
                rawMessage
            }
        if (message in messages) return
        if (messages.size >= MAX_MESSAGES || size + message.length > MAX_CHARS) {
            omitted++
            return
        }
        messages.add(message)
        size += message.length
    }

    @Synchronized
    fun failureMessage(): String =
        if (messages.isEmpty() && !ambiguous && unmatched?.hasMessages() == true) {
            "Rebuild Project failed. The following errors came from a build started during the rebuild, " +
                "but IntelliJ did not identify it as this rebuild; verify them in the Build window:\n\n" +
                unmatched?.formatMessages().orEmpty()
        } else if (messages.isEmpty()) {
            "Rebuild Project failed, but IntelliJ did not provide compiler error details for this build. " +
                "See the IDE Build window."
        } else {
            "Rebuild Project failed with errors:\n\n" + formatMessages()
        }

    @Synchronized
    private fun hasMessages() = messages.isNotEmpty()

    @Synchronized
    private fun formatMessages(): String =
        buildString {
            append(messages.joinToString("\n\n"))
            if (omitted > 0) append("\n\n$omitted additional error(s) omitted; see the IDE Build window.")
        }

    private companion object {
        const val MAX_MESSAGES = 50
        const val MAX_CHARS = 24_000
    }
}
