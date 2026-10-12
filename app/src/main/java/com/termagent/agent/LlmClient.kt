package com.termagent.agent

import android.content.Context
import com.termagent.R
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

/**
 * Shared JSON codec.
 *
 * * `explicitNulls = false` — do not send `"content": null` on tool messages, some servers
 *   reject it.
 * * `ignoreUnknownKeys = true` — the many OpenAI-compatible servers all add extra fields.
 */
@OptIn(ExperimentalSerializationApi::class)
val ChatJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = true
    coerceInputValues = true
}

/** One step of a streamed completion. */
sealed interface LlmEvent {
    /** Visible assistant text. */
    data class Text(val delta: String) : LlmEvent

    /** Chain-of-thought text (DeepSeek-R1 style models); rendered in a collapsed block. */
    data class Reasoning(val delta: String) : LlmEvent

    /** A fragment of a tool call. Arguments arrive as a JSON string split over many frames. */
    data class ToolCall(
        val index: Int,
        val id: String?,
        val name: String?,
        val argumentsDelta: String?,
    ) : LlmEvent

    data class Finished(val finishReason: String?) : LlmEvent

    /** Token accounting for one request, emitted when the provider reports it. */
    data class Usage(val promptTokens: Int, val completionTokens: Int) : LlmEvent

    data class Failure(val message: String, val cause: Throwable? = null) : LlmEvent
}

/**
 * The streaming seam between [AgentEngine] and the network.
 *
 * A named interface rather than a `LlmClient` reference so the agent loop can be driven
 * by a fake in a JVM test: the loop's contract is "consume [LlmEvent]s, decide what to do
 * next", and none of that needs HTTP, TLS or an Android runtime. The production
 * implementation is [LlmClient]; see `AgentEngineTest`, which never touches the network.
 *
 * Deliberately no default argument for [tools]: Kotlin forbids an override from repeating a
 * default, and every caller in the loop passes the tool list explicitly anyway. Public (rather
 * than internal) only so the public [AgentEngine] constructor can name it without tripping
 * Kotlin's "public function exposes its internal parameter type" rule.
 */
interface LlmStreamer {
    fun stream(
        config: ApiConfig,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>?,
    ): Flow<LlmEvent>
}

/**
 * Minimal `text/event-stream` client for `POST {baseUrl}/chat/completions`.
 *
 * Uses `okhttp-sse`, so events are framed by the spec rather than by hand-rolled
 * newline splitting. The read timeout is disabled for the request because a long
 * reasoning turn can be silent for a while before the first token lands.
 */
/**
 * Streaming client for an OpenAI-compatible `/chat/completions` endpoint.
 *
 * @param context used **only** to resolve a handful of user-facing error strings
 *   (`R.string.error_request_failed` and friends). Callers must pass an application context;
 *   nothing else about an Android `Context` is retained.
 */
class LlmClient(
    private val http: OkHttpClient,
    private val context: Context,
) : LlmStreamer {

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val SSE_DONE = "[DONE]"

        /** OkHttp client tuned for streaming: no read timeout, aggressive connect timeout. */
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    override fun stream(
        config: ApiConfig,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>?,
    ): Flow<LlmEvent> = callbackFlow {
        val payload = ChatRequest(
            model = config.model,
            messages = messages,
            stream = true,
            tools = tools?.takeIf { it.isNotEmpty() },
            toolChoice = if (tools.isNullOrEmpty()) null else "auto",
            temperature = config.temperature,
            maxTokens = config.maxTokens,
            // Ask for a trailing usage frame so cost can be shown; servers that don't support
            // it simply ignore the field.
            streamOptions = StreamOptions(includeUsage = true),
        )

        val request = try {
            Request.Builder()
                .url(config.chatCompletionsUrl)
                .header("Accept", "text/event-stream")
                .apply {
                    if (config.apiKey.isNotBlank()) {
                        header("Authorization", "Bearer ${config.apiKey.trim()}")
                    }
                }
                .post(ChatJson.encodeToString(ChatRequest.serializer(), payload).toRequestBody(JSON_MEDIA))
                .build()
        } catch (t: Throwable) {
            trySend(LlmEvent.Failure("Invalid endpoint URL: ${config.chatCompletionsUrl}"))
            close()
            return@callbackFlow
        }

        val listener = object : EventSourceListener() {

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == SSE_DONE) {
                    trySend(LlmEvent.Finished(null))
                    close()
                    return
                }
                val chunk = runCatching {
                    ChatJson.decodeFromString(ChatChunk.serializer(), data)
                }.getOrElse { error ->
                    // A malformed frame is not fatal; OpenAI-compatible servers emit
                    // plenty of keep-alive noise.
                    android.util.Log.w("LlmClient", "Skipping unparseable SSE frame", error)
                    return
                }

                // The usage frame arrives with an empty `choices` array, so it must be read
                // before bailing out on a missing choice.
                chunk.usage?.let { usage ->
                    if (usage.promptTokens > 0 || usage.completionTokens > 0) {
                        trySend(LlmEvent.Usage(usage.promptTokens, usage.completionTokens))
                    }
                }

                val choice = chunk.choices.firstOrNull() ?: return
                choice.delta?.let { delta ->
                    delta.reasoningContent?.takeIf { it.isNotEmpty() }
                        ?.let { trySend(LlmEvent.Reasoning(it)) }
                    delta.content?.takeIf { it.isNotEmpty() }
                        ?.let { trySend(LlmEvent.Text(it)) }
                    delta.toolCalls?.forEach { call ->
                        trySend(
                            LlmEvent.ToolCall(
                                index = call.index,
                                id = call.id,
                                name = call.function?.name,
                                argumentsDelta = call.function?.arguments,
                            ),
                        )
                    }
                }
                choice.finishReason?.let { trySend(LlmEvent.Finished(it)) }
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val message = buildString {
                    append(context.getString(R.string.error_request_failed))
                    if (response != null) append(" (HTTP ${response.code})")
                    val body = response?.let { res -> runCatching { res.body?.string() }.getOrNull() }
                    val detail = body?.takeIf { it.isNotBlank() }?.let(::extractApiError) ?: t?.message
                    if (!detail.isNullOrBlank()) append(": ").append(detail)
                }
                trySend(LlmEvent.Failure(message, t))
                close()
            }
        }

        val eventSource = EventSources.createFactory(http).newEventSource(request, listener)

        awaitClose { eventSource.cancel() }
    }

    /** Turn an error body into something readable, falling back to the raw text. */
    private fun extractApiError(body: String): String = runCatching {
        ChatJson.decodeFromString(ApiErrorEnvelope.serializer(), body).error?.message
    }.getOrNull() ?: body.take(500)
}
