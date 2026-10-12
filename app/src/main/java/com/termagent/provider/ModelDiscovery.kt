package com.termagent.provider

import com.termagent.agent.ApiConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fetches the list of models a provider advertises, via the OpenAI-compatible
 * `GET {baseUrl}/models` endpoint — the same call devx makes in `cli/index.js`
 * (`fetchModels`).
 *
 * The shared OkHttp client disables the read timeout (a deliberate choice for SSE), so a
 * short-timeout derivative is used here: a hung `/models` call must not wedge the UI.
 */
object ModelDiscovery {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class ModelsResponse(val data: List<ModelEntry> = emptyList())

    @Serializable
    private data class ModelEntry(val id: String = "")

    @Serializable
    private data class ErrorEnvelope(val error: ErrorBody? = null)

    @Serializable
    private data class ErrorBody(val message: String? = null)

    /** Models sorted by id; fails with a readable message on 401 / other HTTP errors. */
    suspend fun fetch(http: OkHttpClient, baseUrl: String, apiKey: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = ApiConfig.modelsUrl(baseUrl)
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .apply {
                        if (apiKey.isNotBlank()) {
                            header("Authorization", "Bearer ${apiKey.trim()}")
                        }
                    }
                    .get()
                    .build()

                val client = http.newBuilder()
                    .readTimeout(30, TimeUnit.SECONDS)
                    .callTimeout(45, TimeUnit.SECONDS)
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw IllegalStateException("HTTP ${response.code}: ${errorText(body)}")
                    }
                    json.decodeFromString(ModelsResponse.serializer(), body)
                        .data
                        .map { it.id }
                        .filter { it.isNotBlank() }
                        .sorted()
                }
            }
        }

    private fun errorText(body: String): String {
        if (body.isBlank()) return "no response body"
        val parsed = runCatching {
            json.decodeFromString(ErrorEnvelope.serializer(), body).error?.message
        }.getOrNull()
        return parsed?.takeIf { it.isNotBlank() } ?: body.take(300)
    }
}
