package com.unique.visionmate.engine

import android.content.Context
import org.json.JSONObject

internal data class FeatureModelSpec(
    val feature: Feature,
    val enabled: Boolean,
    val assetPath: String?,
    val inputWidth: Int,
    val inputHeight: Int,
    val mean: FloatArray,
    val std: FloatArray,
    val labels: List<String>,
    val postprocessor: String,
    val confidenceThreshold: Float
)

internal object ModelManifest {

    fun read(context: Context, path: String = "models/manifest.json"): Map<Feature, FeatureModelSpec> {
        val raw = try {
            context.assets.open(path).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Telemetry.w("manifest missing at $path; all model-backed features disabled")
            return emptyMap()
        }
        return parse(raw, path)
    }

    // A malformed manifest must disable features safely, never crash the engine.
    internal fun parse(raw: String, path: String = "models/manifest.json"): Map<Feature, FeatureModelSpec> {
        val features = try {
            JSONObject(raw).optJSONObject("features")
        } catch (e: Exception) {
            Telemetry.w("manifest at $path is malformed (${e.message}); all model-backed features disabled")
            null
        } ?: return emptyMap()
        val out = mutableMapOf<Feature, FeatureModelSpec>()
        val keys = features.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val feature = runCatching { Feature.valueOf(key) }.getOrNull() ?: continue
            // A malformed single entry must not sink the whole manifest.
            val spec = runCatching { features.getJSONObject(key) }.getOrNull() ?: continue
            val enabled = spec.optBoolean("enabled", false)
            val assetPath = spec.optString("asset", "").takeIf { it.isNotEmpty() }
            val mean = spec.floatArrayOrNull("mean").rgbOrDefault(floatArrayOf(0f, 0f, 0f), rejectZero = false)
            val std = spec.floatArrayOrNull("std").rgbOrDefault(floatArrayOf(255f, 255f, 255f), rejectZero = true)
            val labels = spec.stringListOrEmpty("labels")
            out[feature] = FeatureModelSpec(
                feature = feature,
                enabled = enabled,
                assetPath = assetPath,
                inputWidth = spec.optInt("inputWidth", 224),
                inputHeight = spec.optInt("inputHeight", 224),
                mean = mean,
                std = std,
                labels = labels,
                postprocessor = spec.optString("postprocessor", "NONE"),
                confidenceThreshold = spec.optDouble("confidenceThreshold", 0.25).toFloat().coerceIn(0.01f, 0.99f)
            )
        }
        return out
    }

    fun assetExists(context: Context, path: String): Boolean = try {
        context.assets.open(path).use { true }
    } catch (_: Exception) {
        false
    }

    private fun FloatArray?.rgbOrDefault(defaultValue: FloatArray, rejectZero: Boolean): FloatArray {
        if (this == null || size != 3 || (rejectZero && any { it == 0f })) return defaultValue
        return this
    }

    private fun JSONObject.floatArrayOrNull(key: String): FloatArray? {
        val array = optJSONArray(key) ?: return null
        return runCatching {
            FloatArray(array.length()) { i -> array.getDouble(i).toFloat() }
        }.getOrNull()
    }

    private fun JSONObject.stringListOrEmpty(key: String): List<String> {
        val array = optJSONArray(key) ?: return emptyList()
        return runCatching {
            List(array.length()) { i -> array.getString(i) }
        }.getOrDefault(emptyList())
    }
}
