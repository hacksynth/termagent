package com.termagent.data

import android.content.Context
import androidx.core.content.edit
import com.termagent.agent.ApiConfig
import com.termagent.provider.ProviderRegistry
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** How the app picks its colour scheme. */
enum class ThemeMode(val storageKey: String) {
    Dark("dark"),
    Light("light"),
    System("system"),
    MonetDark("monet_dark"),
    MonetLight("monet_light");

    companion object {
        fun fromKey(key: String?): ThemeMode =
            entries.firstOrNull { it.storageKey == key } ?: Dark
    }

    /** True for the wallpaper-sampled modes, whose look we cannot restyle per brand. */
    val isMonet: Boolean get() = this == MonetDark || this == MonetLight

    /** The plain light/dark counterpart of a Monet mode (`System` maps to itself). */
    fun withoutMonet(): ThemeMode = when (this) {
        MonetDark -> Dark
        MonetLight -> Light
        else -> this
    }
}

/**
 * The brand palette the shell paints with — an axis *orthogonal* to [ThemeMode], so
 * "DeepSeek, follow system" is expressible without enumerating every combination.
 */
enum class ThemeStyle(val storageKey: String) {
    /** The built-in blue/grey shell. */
    Default("default"),

    /** DeepSeek's brand look: white + `#4D6BFE` in light, ink-blue in dark. */
    DeepSeek("deepseek");

    companion object {
        fun fromKey(key: String?): ThemeStyle =
            entries.firstOrNull { it.storageKey == key } ?: Default
    }
}

/**
 * Persisted user settings.
 *
 * Backed by [android.content.SharedPreferences] — the data is tiny (an API key, a model
 * name, a theme enum), and this avoids pulling in DataStore for a handful of values.
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Catalogue id of the active provider (see [com.termagent.provider.ProviderRegistry]).
     * Devx keeps a distinct endpoint + key per provider; the two maps below reproduce that so
     * switching back to a provider restores what was typed for it.
     */
    var providerId: String
        get() = prefs.getString(KEY_PROVIDER_ID, null) ?: ProviderRegistry.DEFAULT_ID
        set(value) = prefs.edit { putString(KEY_PROVIDER_ID, value) }

    /** API key remembered for [provider], or "" when none was stored. */
    fun apiKeyFor(provider: String): String =
        stringMap(KEY_API_KEYS)[provider].orEmpty()

    fun setApiKeyFor(provider: String, value: String) {
        putStringMap(KEY_API_KEYS, stringMap(KEY_API_KEYS) + (provider to value))
    }

    /** Base URL override remembered for [provider]; null means "use the preset's URL". */
    fun baseUrlFor(provider: String): String? = stringMap(KEY_BASE_URLS)[provider]

    fun setBaseUrlFor(provider: String, value: String) {
        putStringMap(KEY_BASE_URLS, stringMap(KEY_BASE_URLS) + (provider to value))
    }

    private fun stringMap(key: String): Map<String, String> = runCatching {
        json.decodeFromString(STRING_MAP, prefs.getString(key, null) ?: "{}")
    }.getOrElse { emptyMap() }

    private fun putStringMap(key: String, value: Map<String, String>) {
        prefs.edit { putString(key, json.encodeToString(STRING_MAP, value)) }
    }

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, null) ?: ApiConfig.DEFAULT_BASE_URL
        set(value) = prefs.edit { putString(KEY_BASE_URL, value) }

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_API_KEY, value) }

    var model: String
        get() = prefs.getString(KEY_MODEL, null) ?: ApiConfig.DEFAULT_MODEL
        set(value) = prefs.edit { putString(KEY_MODEL, value) }

    var temperature: Float
        get() = prefs.getFloat(KEY_TEMPERATURE, 0.3f)
        set(value) = prefs.edit { putFloat(KEY_TEMPERATURE, value) }

    var maxTokens: Int
        get() = prefs.getInt(KEY_MAX_TOKENS, 4096)
        set(value) = prefs.edit { putInt(KEY_MAX_TOKENS, value) }

    var systemPrompt: String
        get() = prefs.getString(KEY_SYSTEM_PROMPT, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_SYSTEM_PROMPT, value) }

    /**
     * Safety switch: when off, `run_shell` is withheld from the model, so it can only read
     * and write files. Defaults to on because the app is a shell agent.
     */
    var allowCommandExecution: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_EXEC, true)
        set(value) = prefs.edit { putBoolean(KEY_ALLOW_EXEC, value) }

    var themeMode: ThemeMode
        get() = ThemeMode.fromKey(prefs.getString(KEY_THEME, null))
        set(value) = prefs.edit { putString(KEY_THEME, value.storageKey) }

    var themeStyle: ThemeStyle
        get() = ThemeStyle.fromKey(prefs.getString(KEY_THEME_STYLE, null))
        set(value) = prefs.edit { putString(KEY_THEME_STYLE, value.storageKey) }

    /** Set once the Termux bootstrap has been unpacked successfully. */
    var bootstrapInstalled: Boolean
        get() = prefs.getBoolean(KEY_BOOTSTRAP, false)
        set(value) = prefs.edit { putBoolean(KEY_BOOTSTRAP, value) }

    /** The endpoint/model/etc. as the agent layer wants it. */
    fun apiConfig(): ApiConfig = ApiConfig(
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        temperature = temperature.toDouble(),
        maxTokens = maxTokens,
        systemPrompt = systemPrompt.takeIf { it.isNotBlank() },
    )

    val isConfigured: Boolean
        get() = apiKey.isNotBlank() || baseUrl.contains("localhost") || baseUrl.contains("127.0.0.1")

    private companion object {
        const val PREFS_NAME = "termagent_settings"
        const val KEY_BASE_URL = "base_url"
        const val KEY_API_KEY = "api_key"
        const val KEY_MODEL = "model"
        const val KEY_TEMPERATURE = "temperature"
        const val KEY_MAX_TOKENS = "max_tokens"
        const val KEY_SYSTEM_PROMPT = "system_prompt"
        const val KEY_ALLOW_EXEC = "allow_command_execution"
        const val KEY_THEME = "theme_mode"
        const val KEY_THEME_STYLE = "theme_style"
        const val KEY_BOOTSTRAP = "bootstrap_installed"
        const val KEY_PROVIDER_ID = "provider_id"
        const val KEY_API_KEYS = "provider_api_keys"
        const val KEY_BASE_URLS = "provider_base_urls"

        /** Serializer for the two JSON-encoded `provider -> value` maps above. */
        val STRING_MAP = MapSerializer(String.serializer(), String.serializer())
    }
}
