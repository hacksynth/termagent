package com.termagent.agent

import com.termagent.data.Attachment
import com.termagent.data.ChatEntry
import com.termagent.data.Role
import java.io.File

/**
 * Rebuilds the wire conversation from the stored chat entries.
 *
 * This is the piece that has to be exactly right: an assistant turn that asked for tools must be
 * replayed as `assistant(tool_calls=[…])` followed by one `tool` message per call, or the server
 * rejects the whole request — and the failure only shows up on the *next* turn, long after the
 * turn that produced the entries. It lives here, as a pure function of the entries, rather than
 * inside the view model so that these rules are unit-testable: `ApiHistoryTest` covers them
 * without a repository, a view model or an Android runtime.
 *
 * The entry list is the persistent log of a conversation, and it is *not* already in wire shape:
 *
 *  * a `Tool` entry is a UI terminal card, which stores both the streamed [TerminalBlock.output]
 *    and the complete [TerminalBlock.result] — only one of them is the tool reply;
 *  * a card may still be running (no result at all) when the turn is rebuilt;
 *  * a card can be orphaned — its requesting assistant entry was deleted, or trimmed away;
 *  * system entries are stored but injected separately by [AgentEngine].
 *
 * Everything here is a pure function so it can be reasoned about in isolation; see
 * `ApiHistoryTest` for the cases that matter.
 */
internal object ApiHistory {

    /**
     * How many messages are sent at most, counting from the newest.
     *
     * A cheap bound on the prompt. Note it is a *message* count, not a token count — see the
     * README section on context handling for why that is a known limitation rather than a design.
     */
    const val MAX_MESSAGES = 40

    /**
     * @param entries the conversation, oldest first.
     * @param fileOf resolves an attachment record to its file on disk; called only for entries that
     *   actually carry attachments. A file that has since been deleted is handled by
     *   [UserContent], which notes its absence in the prompt rather than failing the turn.
     */
    fun build(entries: List<ChatEntry>, fileOf: (Attachment) -> File): List<ChatMessage> {
        val messages = ArrayList<ChatMessage>(entries.size)

        var index = 0
        while (index < entries.size) {
            val entry = entries[index]

            when (entry.role) {
                Role.User -> {
                    // Attachments are re-expanded here, not just the first time: the same entry is
                    // replayed on every turn of the conversation, and a historical image has to be
                    // re-encoded as a content part each time (UserContent caches the base64).
                    val content = UserContent.build(
                        text = entry.text,
                        attachments = entry.attachments,
                        fileOf = fileOf,
                    )
                    if (content != null) messages += ChatMessage.user(content)
                    index++
                }

                Role.Assistant -> {
                    val toolEntries = ArrayList<ChatEntry>()
                    var cursor = index + 1
                    while (cursor < entries.size && entries[cursor].role == Role.Tool) {
                        toolEntries += entries[cursor]
                        cursor++
                    }

                    val toolCalls = toolEntries.mapNotNull { toolEntry ->
                        val block = toolEntry.terminal ?: return@mapNotNull null
                        ToolCall(
                            id = block.toolCallId,
                            function = FunctionCall(name = block.toolName, arguments = block.arguments),
                        )
                    }

                    if (entry.text.isNotBlank() || toolCalls.isNotEmpty()) {
                        messages += ChatMessage(
                            role = ChatMessage.ROLE_ASSISTANT,
                            content = MessageContent.of(entry.text),
                            toolCalls = toolCalls.takeIf { it.isNotEmpty() },
                        )

                        // A `tool` message must directly follow the assistant message that
                        // requested it — and only for calls that actually ran, otherwise some
                        // servers error out on an unanswered tool_call.
                        toolEntries.forEach { toolEntry ->
                            val block = toolEntry.terminal ?: return@forEach
                            val result = block.result ?: block.output
                            if (result.isNotBlank()) {
                                messages += ChatMessage.tool(block.toolCallId, block.toolName, result)
                            }
                        }
                    }

                    index = cursor
                }

                Role.Tool -> index++ // orphaned tool card (no preceding assistant) — skip
                Role.System -> index++ // the system prompt is injected by the engine
            }
        }

        val trimmed = if (messages.size > MAX_MESSAGES) {
            messages.subList(messages.size - MAX_MESSAGES, messages.size).toList()
        } else {
            messages
        }

        // The API requires the first message after `system` to be a user message.
        return trimmed.dropWhile { it.role != ChatMessage.ROLE_USER }
    }
}
