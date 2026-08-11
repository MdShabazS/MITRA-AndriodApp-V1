package com.unique.visionmate.engine

import android.content.Context
import android.os.SystemClock
import com.unique.visionmate.engine.features.DayNightGate
import com.unique.visionmate.engine.features.FeatureRunner
import com.unique.visionmate.engine.features.OcrFeature
import com.unique.visionmate.engine.features.TfliteFeatureRunner
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

internal class Pipeline(
    private val context: Context,
    private val config: EngineConfig,
    private val flags: FeatureFlags,
    private val observer: HazardObserver
) {
    private val dayNightGate = DayNightGate(config.luminanceThreshold)
    private val manifest = ModelManifest.read(context)

    private val sceneRunner: FeatureRunner? = makeTflite(Feature.SCENE)
    private val fireSmokeRunner: FeatureRunner? = makeTflite(Feature.FIRE_SMOKE)
    private val wetDryRunner: FeatureRunner? = makeTflite(Feature.WET_DRY)
    private val pedestrianRunner: FeatureRunner? = makeTflite(Feature.PEDESTRIAN)
    private val potholeRunner: FeatureRunner? = makeTflite(Feature.POTHOLE)
    private val electricPoleRunner: FeatureRunner? = makeTflite(Feature.ELECTRIC_POLE)
    private val ocrRunner: FeatureRunner = OcrFeature()

    // Round-robin state: running every heavy detector on every frame makes each frame take seconds
    // (CPU, sequential) so most frames are dropped as stale. We run one heavy detector per frame and
    // reuse the last result for the others. process() runs one frame at a time (maxInFlight = 1), so
    // this counter needs no synchronization.
    private var rrCounter = 0
    private data class CachedDetections(
        val detections: List<Detection>,
        val frameTsMs: Long
    )

    private val lastDetections = ConcurrentHashMap<Feature, CachedDetections>()

    // Scene changes slowly, so re-run it only every Nth frame and reuse the last classification in
    // between. This trims scene inference off most frames, further reducing dropped frames.
    private var sceneCounter = 0
    private var lastScene: SceneType = SceneType.UNKNOWN
    private val sceneInterval = 3

    private fun runnerFor(f: Feature): FeatureRunner? = when (f) {
        Feature.SCENE -> sceneRunner
        Feature.FIRE_SMOKE -> fireSmokeRunner
        Feature.WET_DRY -> wetDryRunner
        Feature.PEDESTRIAN -> pedestrianRunner
        Feature.POTHOLE -> potholeRunner
        Feature.ELECTRIC_POLE -> electricPoleRunner
        Feature.OCR -> ocrRunner
        else -> null
    }

    init {
        val loaded = listOfNotNull(
            sceneRunner, fireSmokeRunner, wetDryRunner, pedestrianRunner, potholeRunner, electricPoleRunner
        ).map { it.feature }
        val tfliteFeatures = listOf(Feature.SCENE, Feature.FIRE_SMOKE, Feature.WET_DRY, Feature.PEDESTRIAN, Feature.POTHOLE, Feature.ELECTRIC_POLE)
        val missing = tfliteFeatures - loaded.toSet()
        Telemetry.i("pipeline ready: dayNight=algorithmic ocr=mlkit tfliteLoaded=$loaded modelMissing=$missing")
    }

    private fun makeTflite(feature: Feature): FeatureRunner? {
        val spec = manifest[feature]
        if (spec == null || !spec.enabled || spec.assetPath == null ||
            !ModelManifest.assetExists(context, spec.assetPath)
        ) {
            observer.onTelemetry(TelemetryEvent.FeatureDisabled(feature, SkipReason.MODEL_MISSING))
            return null
        }
        val runner = TfliteFeatureRunner(context, spec)
        if (!runner.enabled) {
            observer.onTelemetry(TelemetryEvent.FeatureDisabled(feature, SkipReason.MODEL_MISSING))
            return null
        }
        return runner
    }

    suspend fun process(frame: Frame): HazardFrameResult = coroutineScope {
        // ConcurrentHashMap-backed so the parallel feature blocks (below) can write safely.
        val executed = ConcurrentHashMap.newKeySet<Feature>()
        val skipped = ConcurrentHashMap<Feature, SkipReason>()
        val detections = ConcurrentHashMap<Feature, List<Detection>>()
        val latencies = ConcurrentHashMap<Feature, Long>()

        // Stage 1: Day/night gate
        val dnStart = SystemClock.elapsedRealtime()
        val dn: DayNight = if (flags.isEnabled(Feature.DAY_NIGHT)) {
            executed.add(Feature.DAY_NIGHT)
            val evaluated = dayNightGate.evaluate(frame.bitmap)
            // Debug bypass: still log real luminance above, but never gate the rest of the stack.
            if (config.debugDisableNightGate) {
                if (evaluated == DayNight.NIGHT) Telemetry.i("dayNightGate NIGHT overridden to DAY (debugDisableNightGate)")
                DayNight.DAY
            } else evaluated
        } else {
            skipped[Feature.DAY_NIGHT] = SkipReason.FEATURE_DISABLED
            DayNight.DAY
        }
        latencies[Feature.DAY_NIGHT] = SystemClock.elapsedRealtime() - dnStart

        if (dn == DayNight.NIGHT) {
            lastDetections.clear()
            for (f in NIGHT_SKIP_FEATURES) skipped[f] = SkipReason.NIGHT
            observer.onTelemetry(TelemetryEvent.FrameSkippedNight(frame.frameId))
            return@coroutineScope HazardFrameResult(
                frameId = frame.frameId, tsMs = frame.tsMs,
                dayNight = dn, sceneType = null,
                executedFeatures = executed, skippedFeatures = skipped,
                detectionsByFeature = detections, latenciesMs = latencies,
                skipReason = SkipReason.NIGHT
            )
        }

        // Stage 2: Scene — re-run only every Nth frame; reuse the last classification otherwise.
        val scene: SceneType
        if (sceneCounter % sceneInterval == 0) {
            scene = runScene(frame, executed, skipped, detections, latencies)
            lastScene = scene
        } else {
            scene = lastScene
            freshCachedDetections(Feature.SCENE, frame)?.let {
                executed.add(Feature.SCENE)
                detections[Feature.SCENE] = it.detections
                latencies[Feature.SCENE] = 0
            }
        }
        sceneCounter++

        // Stage 3+4: sequential execution (parallel GPU produced native tombstones; sequential CPU
        // is the safe path). To keep per-frame latency bounded, fire/smoke runs every frame
        // (safety-critical) and the remaining heavy detectors are round-robined one per frame, with
        // the last result reused for the others.
        runFeature(Feature.FIRE_SMOKE, fireSmokeRunner, frame, config.cpuTimeoutMs,
            executed, skipped, detections, latencies)

        val rotation = mutableListOf(Feature.WET_DRY, Feature.PEDESTRIAN, Feature.OCR)
        if (scene == SceneType.OUTDOOR) {
            rotation.add(Feature.POTHOLE)
            rotation.add(Feature.ELECTRIC_POLE)
        } else {
            skipped[Feature.POTHOLE] = SkipReason.SCENE_INDOOR
            skipped[Feature.ELECTRIC_POLE] = SkipReason.SCENE_INDOOR
            lastDetections.remove(Feature.POTHOLE)
            lastDetections.remove(Feature.ELECTRIC_POLE)
        }

        val runNow = rotation[rrCounter % rotation.size]
        rrCounter++
        for (f in rotation) {
            if (f == runNow) {
                val timeout = if (f == Feature.OCR) config.ocrTimeoutMs else config.cpuTimeoutMs
                runFeature(f, runnerFor(f), frame, timeout, executed, skipped, detections, latencies)
            } else {
                // Not scheduled this frame — reuse only a recent result. Old hazards caused stale
                // spoken warnings during long hardware runs when the RTSP feed had accumulated lag.
                val cached = freshCachedDetections(f, frame)
                if (cached != null) {
                    executed.add(f)
                    detections[f] = cached.detections
                    latencies[f] = 0
                }
                // else: no result yet; leave it absent until its first turn.
            }
        }

        return@coroutineScope HazardFrameResult(
            frameId = frame.frameId, tsMs = frame.tsMs,
            dayNight = dn, sceneType = scene,
            executedFeatures = executed, skippedFeatures = skipped,
            detectionsByFeature = detections, latenciesMs = latencies,
            skipReason = null
        )
    }

    private suspend fun runScene(
        frame: Frame,
        executed: MutableSet<Feature>,
        skipped: MutableMap<Feature, SkipReason>,
        detections: MutableMap<Feature, List<Detection>>,
        latencies: MutableMap<Feature, Long>
    ): SceneType {
        if (!flags.isEnabled(Feature.SCENE)) {
            skipped[Feature.SCENE] = SkipReason.FEATURE_DISABLED
            return SceneType.UNKNOWN
        }
        val runner = sceneRunner
        if (runner == null || !runner.enabled) {
            skipped[Feature.SCENE] = SkipReason.MODEL_MISSING
            return SceneType.UNKNOWN
        }
        val start = SystemClock.elapsedRealtime()
        val result = try {
            withTimeout(config.gpuTimeoutMs) { runner.run(frame.bitmap) }
        } catch (_: TimeoutCancellationException) {
            skipped[Feature.SCENE] = SkipReason.FEATURE_TIMEOUT
            observer.onTelemetry(TelemetryEvent.FeatureTimeout(Feature.SCENE, frame.frameId))
            latencies[Feature.SCENE] = SystemClock.elapsedRealtime() - start
            return SceneType.UNKNOWN
        } catch (e: Exception) {
            skipped[Feature.SCENE] = SkipReason.EXCEPTION
            observer.onTelemetry(TelemetryEvent.FeatureException(Feature.SCENE, e.message ?: e::class.java.simpleName))
            latencies[Feature.SCENE] = SystemClock.elapsedRealtime() - start
            return SceneType.UNKNOWN
        }
        latencies[Feature.SCENE] = SystemClock.elapsedRealtime() - start
        executed.add(Feature.SCENE)
        detections[Feature.SCENE] = result
        lastDetections[Feature.SCENE] = CachedDetections(result, frame.tsMs)
        Telemetry.i("SCENE frame=${frame.frameId} top3=${result.take(3).joinToString { "${it.label}:${"%.3f".format(it.score)}" }}")
        val top = result.firstOrNull()?.label?.lowercase() ?: return SceneType.UNKNOWN
        return when {
            "outdoor" in top -> SceneType.OUTDOOR
            "indoor" in top -> SceneType.INDOOR
            else -> SceneType.UNKNOWN
        }
    }

    private suspend fun runFeature(
        feature: Feature,
        runner: FeatureRunner?,
        frame: Frame,
        timeoutMs: Long,
        executed: MutableSet<Feature>,
        skipped: MutableMap<Feature, SkipReason>,
        detections: MutableMap<Feature, List<Detection>>,
        latencies: MutableMap<Feature, Long>
    ) {
        if (!flags.isEnabled(feature)) {
            skipped[feature] = SkipReason.FEATURE_DISABLED
            return
        }
        if (runner == null || !runner.enabled) {
            skipped[feature] = SkipReason.MODEL_MISSING
            return
        }
        val start = SystemClock.elapsedRealtime()
        try {
            val out = withTimeout(timeoutMs) { runner.run(frame.bitmap) }
            executed.add(feature)
            detections[feature] = out
            lastDetections[feature] = CachedDetections(out, frame.tsMs)
        } catch (_: TimeoutCancellationException) {
            skipped[feature] = SkipReason.FEATURE_TIMEOUT
            observer.onTelemetry(TelemetryEvent.FeatureTimeout(feature, frame.frameId))
        } catch (e: Exception) {
            skipped[feature] = SkipReason.EXCEPTION
            observer.onTelemetry(TelemetryEvent.FeatureException(feature, e.message ?: e::class.java.simpleName))
        } finally {
            latencies[feature] = SystemClock.elapsedRealtime() - start
        }
    }

    fun close() {
        listOfNotNull(sceneRunner, fireSmokeRunner, wetDryRunner, pedestrianRunner, potholeRunner, electricPoleRunner, ocrRunner)
            .forEach { it.close() }
    }

    private fun freshCachedDetections(feature: Feature, frame: Frame): CachedDetections? {
        val cached = lastDetections[feature] ?: return null
        val ageMs = frame.tsMs - cached.frameTsMs
        if (ageMs in 0..CACHED_DETECTION_TTL_MS) return cached
        lastDetections.remove(feature, cached)
        return null
    }

    companion object {
        private const val CACHED_DETECTION_TTL_MS = 2_500L

        private val NIGHT_SKIP_FEATURES = listOf(
            Feature.SCENE, Feature.FIRE_SMOKE, Feature.WET_DRY,
            Feature.OCR, Feature.PEDESTRIAN, Feature.POTHOLE, Feature.ELECTRIC_POLE
        )
    }
}
