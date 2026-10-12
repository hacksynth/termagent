package com.termagent.provider

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One entry of devx's `ALL_PROVIDERS` catalogue: a preset provider id, a display label and
 * the OpenAI-compatible base URL used to reach it.
 *
 * Ported verbatim from termux-dev (`dist/cli/providers.js`, v1.6.0) and shipped as the bundled
 * asset `providers/providers.json`, so the list can be refreshed without touching code.
 */
@Serializable
data class ProviderPreset(
    /** Stable id, e.g. `deepseek`. Also the key for the per-provider key / base-URL maps. */
    val value: String,
    /** Human-readable name shown in the picker. */
    val label: String,
    /** Base URL of the provider's OpenAI-compatible API. Empty for the `custom` entry. */
    val baseUrl: String = "",
)

/**
 * Loads and caches the bundled provider catalogue (178 presets, including `custom`).
 *
 * The asset is ~20 KB and decoded once, so a plain [lazy] is enough — no background loader.
 */
class ProviderRegistry(context: Context) {

    private val appContext = context.applicationContext

    private val json = Json { ignoreUnknownKeys = true }

    /** All presets, in catalogue order. */
    val presets: List<ProviderPreset> by lazy { load() }

    private fun load(): List<ProviderPreset> = runCatching {
        appContext.assets.open(ASSET).bufferedReader().use { it.readText() }
    }.mapCatching { text ->
        json.decodeFromString(ListSerializer(ProviderPreset.serializer()), text)
    }.getOrElse { emptyList() }

    fun byId(id: String): ProviderPreset? = presets.firstOrNull { it.value == id }

    /** Case-insensitive substring match over id and label, for the picker's filter box. */
    fun search(query: String): List<ProviderPreset> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return presets
        return presets.filter {
            it.value.lowercase().contains(q) || it.label.lowercase().contains(q)
        }
    }

    /** Label for [id], falling back to the id itself (or "Custom" when unset). */
    fun labelFor(id: String): String =
        byId(id)?.label ?: id.ifBlank { "Custom" }

    companion object {
        const val ASSET = "providers/providers.json"

        /** The catch-all entry that asks the user for a raw base URL. */
        const val CUSTOM_ID = "custom"

        /** Preset selected on a fresh install. */
        const val DEFAULT_ID = "openai"
    }
}
