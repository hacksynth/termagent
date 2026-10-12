package com.termagent.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Wire model for an OpenAI-compatible `/chat/completions` endpoint.
 *
 * Everything is `@Serializable` so the same classes are used for requests *and* responses;
 * the shared [ChatJson] instance omits nulls on the way out and ignores unknown fields on
 * the way in, which keeps this working across the many "OpenAI-compatible" servers
 * (vLLM, llama.cpp, Ollama, DeepSeek, OpenRouter, …).
 */

/**
 * One message of the wire conversation.
 *
 * [content] is a [MessageContent], which serialises to *either* a plain JSON string (text-only,
 * what almost every message is) or an array of typed parts (the multimodal form used to attach
 * images). See [MessageContent] for why that union is modelled explicitly.
 */
@Serializable
data class ChatMessage(
    val role: String,
    val content: MessageContent? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null,
) {
    /** Visible text of this message; non-text parts (images) contribute nothing. */
    val textContent: String? get() = content?.plainText

    companion object {
        const val ROLE_SYSTEM = "system"
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_TOOL = "tool"

        fun system(content: String) = ChatMessage(ROLE_SYSTEM, MessageContent.Text(content))

        fun user(content: String) = ChatMessage(ROLE_USER, MessageContent.Text(content))

        /** User turn carrying images and/or files alongside an optional caption. */
        fun user(content: MessageContent) = ChatMessage(ROLE_USER, content)

        fun assistant(content: String) = ChatMessage(ROLE_ASSISTANT, MessageContent.Text(content))

        fun tool(toolCallId: String, name: String, content: String) =
            ChatMessage(
                ROLE_TOOL,
                MessageContent.Text(content),
                toolCallId = toolCallId,
                name = name,
            )
    }
}

@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall,
)

@Serializable
data class FunctionCall(
    val name: String,
    val arguments: String = "{}",
)

@Serializable
data class ToolDefinition(
    val type: String = "function",
    val function: FunctionDefinition,
)

@Serializable
data class FunctionDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = true,
    val tools: List<ToolDefinition>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null,
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    /**
     * Asks the server to append a final frame carrying [Usage]. Ignored by servers that do
     * not know it, which is why usage display degrades gracefully.
     */
    @SerialName("stream_options") val streamOptions: StreamOptions? = null,
)

/** Body of the `stream_options` request field. */
@Serializable
data class StreamOptions(
    @SerialName("include_usage") val includeUsage: Boolean = true,
)

// ------------------------------------------------------------------ streamed chunks

@Serializable
data class ChatChunk(
    val id: String? = null,
    val model: String? = null,
    val choices: List<StreamChoice> = emptyList(),
    /** Present on the final frame when `stream_options.include_usage` was requested. */
    val usage: Usage? = null,
)

/** Token accounting returned by the provider. */
@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
)

@Serializable
data class StreamChoice(
    val index: Int = 0,
    val delta: Delta? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class Delta(
    val role: String? = null,
    val content: String? = null,
    /** Emitted by reasoning models (DeepSeek-R1, QwQ, …). */
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallDelta>? = null,
)

@Serializable
data class ToolCallDelta(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    val function: FunctionCallDelta? = null,
)

@Serializable
data class FunctionCallDelta(
    val name: String? = null,
    val arguments: String? = null,
)

/** Error envelope, so HTTP 4xx bodies can be surfaced to the user verbatim. */
@Serializable
data class ApiErrorEnvelope(val error: ApiError? = null)

@Serializable
data class ApiError(val message: String? = null, val type: String? = null, val code: String? = null)

// ------------------------------------------------------------------ request models

/** User-configurable endpoint settings (persisted in [com.termagent.data.SettingsStore]). */
data class ApiConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
    val temperature: Double = 0.3,
    val maxTokens: Int = 4096,
    val systemPrompt: String? = null,
) {
    /** Base URL normalised to carry a `/v1` segment exactly once. */
    val versionedBaseUrl: String
        get() = normalizeBase(baseUrl)

    val chatCompletionsUrl: String
        get() = "$versionedBaseUrl/chat/completions"

    /** `GET` endpoint listing the models a provider offers. */
    val modelsUrl: String
        get() = "$versionedBaseUrl/models"

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4o-mini"

        /** Tolerates base URLs with or without a trailing `/v1` (and with a trailing slash). */
        fun normalizeBase(baseUrl: String): String {
            val trimmed = baseUrl.trim().trimEnd('/')
            return if (trimmed.endsWith("/v1") || trimmed.contains("/v1/")) {
                trimmed
            } else {
                "$trimmed/v1"
            }
        }

        fun modelsUrl(baseUrl: String): String = "${normalizeBase(baseUrl)}/models"
    }
}
