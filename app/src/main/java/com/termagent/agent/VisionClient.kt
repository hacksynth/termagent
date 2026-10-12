package com.termagent.agent

import android.content.Context
import android.util.Log
import com.termagent.R
import com.termagent.data.ImagePrep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * One-shot multimodal requests, behind the agent's `view_image` tool.
 *
 * ## Why a separate, non-streaming request instead of putting the pixels in the agent's history
 *
 * The agent loop is a *conversation*: every message is replayed on every iteration, and a model that
 * has already been given an image would receive those pixels again on every subsequent turn — a
 * base64 JPEG repeated 6 times in one prompt. Worse, an image part in the history is rejected
 * outright by a text-only model, so one screenshot would break the rest of the conversation.
 *
 * So `view_image` inverts the flow: the pixels go into a *throwaway* request, and only the
 * resulting prose enters the agent loop. The picture is understood once, at a bounded cost, and the
 * transcript stays text.
 *
 * ## Why it is not `stream`
 *
 * The tool needs a complete string to hand back, and a non-streaming request is simpler to parse and
 * to error-check. It also lets the request use its own (finite) read timeout, whereas the streaming
 * client deliberately has none.
 */
class VisionClient(
    http: OkHttpClient,
    private val context: Context,
    /** Current endpoint + credentials; read per call so a settings change takes effect at once. */
    private val config: () -> ApiConfig,
) {

    /**
     * Cloned from the shared client so the vision call gets a real read timeout.
     *
     * The shared client sets `readTimeout(0)` because an SSE stream must never be cut off. Here the
     * opposite is true: a stuck vision request must fail, otherwise the tool call hangs the whole
     * agent turn. `callTimeout` bounds the total, including image upload.
     */
    private val visionHttp: OkHttpClient = http.newBuilder()
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(READ_TIMEOUT_SECONDS + 15, TimeUnit.SECONDS)
        .build()

    /** Whether there is an endpoint + model to send the image to. */
    val isConfigured: Boolean
        get() = config().let { it.model.isNotBlank() && (it.apiKey.isNotBlank() || isLocal(it)) }

    /**
     * Ask the configured model what [file] shows.
     *
     * @param question what to look for; a specific question gives a far more useful answer than an
     *   open-ended one, which is why the tool schema asks for it.
     * @return the model's description, or a human-readable error string. Never throws: a tool result
     *   is data, and a failed vision call should let the agent carry on rather than kill the turn.
     */
    suspend fun describe(file: File, question: String): String = withContext(Dispatchers.IO) {
        val active = config()
        if (!isConfigured) return@withContext "Error: no model is configured."

        // The same downscaling the composer applies, so a 12 MP photo does not turn into a 40 MB
        // request body here either.
        val prepared = ImagePrep.prepare(file)
            ?: return@withContext "Error: ${file.name} could not be decoded as an image " +
                "(supported: PNG, JPEG, WebP, GIF, BMP, HEIC)."

        val payload = ChatRequest(
            model = active.model,
            messages = listOf(
                ChatMessage.user(
                    MessageContent.Parts(
                        listOf(
                            ContentPart.Image(ImagePrep.dataUrl(prepared)),
                            ContentPart.Text(question),
                        ),
                    ),
                ),
            ),
            // Non-streaming: the tool wants one complete string.
            stream = false,
            temperature = 0.1,
            maxTokens = MAX_TOKENS,
        )

        val request = Request.Builder()
            .url(active.chatCompletionsUrl)
            .apply { if (active.apiKey.isNotBlank()) header("Authorization", "Bearer ${active.apiKey.trim()}") }
            .post(
                ChatJson.encodeToString(ChatRequest.serializer(), payload)
                    .toRequestBody(JSON_MEDIA),
            )
            .build()

        runCatching {
            visionHttp.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@runCatching error(
                        context.getString(R.string.error_request_failed) +
                            " (HTTP ${response.code})" +
                            ": " + extractError(body),
                    )
                }

                val parsed = ChatJson.decodeFromString(ChatResponse.serializer(), body)
                val text = parsed.choices.firstOrNull()?.message?.textContent
                if (text.isNullOrBlank()) {
                    error("the model returned an empty description")
                }

                parsed.usage?.let {
                    Log.d(TAG, "view_image tokens: prompt=${it.promptTokens} completion=${it.completionTokens}")
                }
                text
            }
        }.getOrElse { error ->
            Log.w(TAG, "view_image failed", error)
            val message = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
            if (message.startsWith("Error")) message else "Error: $message"
        }
    }

    /** Local endpoints legitimately run without a key. */
    private fun isLocal(config: ApiConfig): Boolean =
        config.baseUrl.contains("localhost") ||
            config.baseUrl.contains("127.0.0.1") ||
            config.baseUrl.contains("0.0.0.0")

    /** Unwrap `{"error":{"message":"…"}}`, falling back to the raw (truncated) body. */
    private fun extractError(body: String): String =
        runCatching { ChatJson.decodeFromString(ApiErrorEnvelope.serializer(), body).error?.message }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: body.take(300).ifBlank { "no response body" }

    private companion object {
        const val TAG = "VisionClient"

        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** Generous, because a vision call is slower than a text one, but still finite. */
        const val READ_TIMEOUT_SECONDS = 120L

        /** Enough for a long transcription, small enough to keep the tool result manageable. */
        const val MAX_TOKENS = 2048
    }
}

/**
 * A non-streaming `/chat/completions` response.
 *
 * Separate from [ChatChunk] (the streamed delta shape) because the two genuinely differ: this one
 * carries a complete `message`, the other a `delta`.
 */
@Serializable
data class ChatResponse(
    val id: String? = null,
    val model: String? = null,
    val choices: List<Choice> = emptyList(),
    val usage: Usage? = null,
) {
    @Serializable
    data class Choice(
        val index: Int = 0,
        val message: ChatMessage? = null,
        @SerialName("finish_reason") val finishReason: String? = null,
    )
}
