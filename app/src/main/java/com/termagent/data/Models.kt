package com.termagent.data

import kotlinx.serialization.Serializable

/** Who produced a message. */
enum class Role { User, Assistant, Tool, System }

/** Visual state of a tool ("terminal") card. */
enum class ToolStatus { Running, Succeeded, Failed }

/**
 * A terminal card: one tool invocation and its streamed output.
 *
 * `toolCallId` links the card to the `assistant` message that requested it, which is how
 * the UI keeps ordering stable while tokens are still streaming in.
 */
@Serializable
data class TerminalBlock(
    val toolCallId: String,
    val toolName: String,
    /** Short preview shown in the card header (normally the command's first line). */
    val command: String,
    /** Raw JSON arguments, replayed verbatim when rebuilding the API history. */
    val arguments: String = "{}",
    /** Streamed output, appended to as the command runs. */
    val output: String = "",
    /** Full tool result text handed back to the model — what a `tool` message replays. */
    val result: String? = null,
    val exitCode: Int? = null,
    val status: ToolStatus = ToolStatus.Running,
) {
    val isRunning: Boolean get() = status == ToolStatus.Running
}

/** One entry in the conversation. */
@Serializable
data class ChatEntry(
    val id: String,
    val role: Role,
    val text: String = "",
    val reasoning: String = "",
    /** Non-null while a tool produced this entry. */
    val terminal: TerminalBlock? = null,
    /**
     * Files the user attached to this message.
     *
     * Only ever set on user entries. The bytes live under `$HOME/attachments/`; these are the
     * metadata records, and they are what both the UI chips and the reconstructed API history are
     * built from.
     */
    val attachments: List<Attachment> = emptyList(),
    val isStreaming: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    /** Prompt tokens billed for this assistant turn (0 when the provider is silent). */
    val promptTokens: Int = 0,
    /** Completion tokens billed for this assistant turn. */
    val completionTokens: Int = 0,
    /** Estimated USD cost for this turn, from the bundled model-pricing table. */
    val costUsd: Double = 0.0,
    /**
     * Why this turn failed (HTTP status + provider message), if it did.
     *
     * Kept on the entry — not just in the transient error bar — so the reason survives a
     * reload and sits next to the message it belongs to. Never replayed to the API.
     */
    val error: String? = null,
)

/** A conversation, as shown in the left-hand (drawer) list. */
@Serializable
data class Conversation(
    val id: String,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val entries: List<ChatEntry> = emptyList(),
) {
    /**
     * Whether the user has not named this conversation yet.
     *
     * The placeholder shown in that case (`R.string.chat_untitled`) is resolved by the UI, not
     * here: this type is persisted, so it must never bake a translated string into the saved
     * conversation JSON.
     */
    val isUntitled: Boolean get() = title.isBlank()
}

/** Result of a bootstrap installation, surfaced on the setup screen. */
sealed interface BootstrapState {
    data object NotInstalled : BootstrapState
    data class Installing(val message: String) : BootstrapState
    data object Installed : BootstrapState
    data class Error(val message: String) : BootstrapState
}
