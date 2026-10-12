package com.termagent.provider

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Per-model USD price per 1,000,000 tokens, as published by devx's `model-pricing.json`.
 */
@Serializable
data class ModelPricing(
    val input: Double = 0.0,
    val output: Double = 0.0,
) {
    val isFree: Boolean get() = input == 0.0 && output == 0.0
}

/**
 * Model metadata ported from devx (`dist/core/models.js` + `dist/core/pricing.js`).
 *
 * Two bundled assets back it — `providers/model-limits.json` (6541 entries, context windows)
 * and `providers/model-pricing.json` (6541 entries, $/Mtok) — and the same fuzzy lookup order
 * devx uses: exact model id, then the segment after the last `/`, then the version-stripped
 * base name, then a family-based heuristic, then a default.
 *
 * Everything is lazily decoded and cached for the process lifetime.
 */
class ModelCatalog(context: Context) {

    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }

    private val limits: Map<String, Int> by lazy { loadLimits() }
    private val pricing: Map<String, ModelPricing> by lazy { loadPricing() }

    // ------------------------------------------------------------------ public API

    /** Context window (in tokens) for [model]; defaults to 128k when unknown. */
    fun contextLimit(model: String): Int {
        val clean = model.trim().lowercase()
        if (clean.isEmpty()) return DEFAULT_CONTEXT
        val short = clean.substringAfterLast('/')
        lookup(limits, clean, short, baseName(short))?.let { return it }
        return heuristicLimit(clean)
    }

    /** Input/output price for [model]; `{0,0}` (free / unknown) when nothing matches. */
    fun pricingFor(model: String): ModelPricing {
        val clean = model.trim().lowercase()
        if (clean.isEmpty()) return ModelPricing()
        if (isFreeHint(clean)) return ModelPricing()
        val short = clean.substringAfterLast('/')
        lookup(pricing, clean, short, baseName(short))?.let { return it }
        return heuristicPricing(clean)
    }

    /** Cost in USD of one completion, matching devx's `calculateCost`. */
    fun estimateCost(model: String, promptTokens: Int, completionTokens: Int): Double {
        val price = pricingFor(model)
        if (price.isFree) return 0.0
        return promptTokens * (price.input / 1_000_000.0) +
            completionTokens * (price.output / 1_000_000.0)
    }

    // ------------------------------------------------------------------ loading

    private fun readAsset(name: String): String? = runCatching {
        appContext.assets.open(name).bufferedReader().use { it.readText() }
    }.getOrNull()

    private fun loadLimits(): Map<String, Int> {
        val text = readAsset(LIMITS_ASSET) ?: return emptyMap()
        return runCatching {
            json.decodeFromString(MapSerializer(String.serializer(), Int.serializer()), text)
        }.getOrElse { emptyMap() }
    }

    private fun loadPricing(): Map<String, ModelPricing> {
        val text = readAsset(PRICING_ASSET) ?: return emptyMap()
        return runCatching {
            json.decodeFromString(
                MapSerializer(String.serializer(), ModelPricing.serializer()),
                text,
            )
        }.getOrElse { emptyMap() }
    }

    // ------------------------------------------------------------------ lookup helpers

    private fun <T> lookup(map: Map<String, T>, clean: String, short: String, base: String): T? =
        map[clean] ?: map[short] ?: map[base]

    /** `deepseek-r1-0528` -> `deepseek-r1`; `x:latest` -> `x`. Mirrors devx. */
    private fun baseName(short: String): String =
        short.replace(VERSION_SUFFIX, "").replace(LATEST_SUFFIX, "")

    private fun isFreeHint(clean: String): Boolean =
        clean.endsWith(":free") ||
            clean.contains("/free") ||
            clean == "free" ||
            clean.contains("free-models/") ||
            clean.contains("stealth") ||
            clean.contains("local") ||
            clean.contains("ollama") ||
            clean.contains("lmstudio")

    private fun heuristicLimit(clean: String): Int = when {
        clean.contains("kimi-k2") || clean.contains("kimi") -> 256_000
        clean.contains("ox-alpha") || clean.contains("stealth") -> 1_048_576
        clean.contains("gemini") -> 1_048_576
        clean.contains("claude-opus-4") || clean.contains("claude-4") -> 1_000_000
        clean.contains("claude") -> 200_000
        clean.contains("gpt-5") -> 1_050_000
        clean.contains("gpt-4o") || clean.contains("gpt-4-turbo") ||
            clean.contains("o1") || clean.contains("o3") -> 128_000
        clean.contains("deepseek") -> 128_000
        clean.contains("qwen3.5") || clean.contains("qwen-3.5") -> 262_144
        clean.contains("qwen") -> 131_072
        clean.contains("glm-5") || clean.contains("glm-4.7") ||
            clean.contains("glm-4.6") -> 200_000
        clean.contains("glm-4") -> 128_000
        clean.contains("minimax") -> 1_000_000
        clean.contains("doubao") -> 256_000
        clean.contains("llama-3") -> 128_000
        clean.contains("mistral-large") || clean.contains("codestral") -> 128_000
        else -> DEFAULT_CONTEXT
    }

    private fun heuristicPricing(clean: String): ModelPricing = when {
        clean.contains("gpt-4o-mini") -> ModelPricing(0.15, 0.60)
        clean.contains("gpt-4o") -> ModelPricing(2.5, 10.0)
        clean.contains("deepseek") -> ModelPricing(0.14, 0.28)
        clean.contains("claude-3-5-sonnet") || clean.contains("claude-3.5-sonnet") ->
            ModelPricing(3.0, 15.0)
        clean.contains("gemini-2.0-flash") || clean.contains("gemini-1.5-flash") ->
            ModelPricing(0.1, 0.4)
        else -> ModelPricing()
    }

    companion object {
        const val LIMITS_ASSET = "providers/model-limits.json"
        const val PRICING_ASSET = "providers/model-pricing.json"
        const val DEFAULT_CONTEXT = 128_000

        private val VERSION_SUFFIX = Regex("-\\d{4,8}$")
        private val LATEST_SUFFIX = Regex(":latest$")
    }
}
