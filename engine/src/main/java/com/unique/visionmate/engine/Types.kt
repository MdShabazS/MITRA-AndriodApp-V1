package com.unique.visionmate.engine

enum class Feature {
    DAY_NIGHT, SCENE, FIRE_SMOKE, WET_DRY, OCR, PEDESTRIAN, POTHOLE, ELECTRIC_POLE
}

enum class DayNight { DAY, NIGHT }

enum class SceneType { INDOOR, OUTDOOR, UNKNOWN }

enum class SkipReason {
    NIGHT,
    SCENE_INDOOR,
    FEATURE_DISABLED,
    MODEL_MISSING,
    POSTPROCESSOR_NOT_IMPLEMENTED,
    FEATURE_TIMEOUT,
    QUEUE_DROP_STALE,
    THERMAL_THROTTLED,
    EXCEPTION
}

data class Detection(
    val label: String,
    val score: Float,
    val boxLtrb: FloatArray? = null
)

data class HazardFrameResult(
    val frameId: Long,
    val tsMs: Long,
    val dayNight: DayNight?,
    val sceneType: SceneType?,
    val executedFeatures: Set<Feature>,
    val skippedFeatures: Map<Feature, SkipReason>,
    val detectionsByFeature: Map<Feature, List<Detection>>,
    val latenciesMs: Map<Feature, Long>,
    val skipReason: SkipReason?
)

data class EngineConfig(
    val maxInFlight: Int = 1,
    val staleAgeMs: Long = 2000,
    val gpuTimeoutMs: Long = 3000,
    val cpuTimeoutMs: Long = 3000,
    val ocrTimeoutMs: Long = 1500,
    val luminanceThreshold: Float = 15f,
    /** Debug: when true, never gate on NIGHT (still logs real luminance). */
    val debugDisableNightGate: Boolean = false
) {
    companion object {
        fun default() = EngineConfig()
    }
}
