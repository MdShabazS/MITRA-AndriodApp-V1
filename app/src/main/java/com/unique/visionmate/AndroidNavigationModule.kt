package com.unique.visionmate

import com.unique.visionmate.engine.DayNight
import com.unique.visionmate.engine.Detection
import com.unique.visionmate.engine.Feature
import com.unique.visionmate.engine.HazardFrameResult
import com.unique.visionmate.engine.SceneType
import kotlin.math.max

data class NavigationDecision(
    val frameId: String,
    val seq: Int?,
    val finalAction: String,
    val finalText: String,
    val direction: String,
    val severity: String,
    val reason: String,
    val shouldSpeak: Boolean,
    val source: Map<String, String>,
    val debugSummary: String
)

/**
 * On-device navigation reasoner. Consumes the latest local hazard result + the cloud perception
 * packet (segmentation coverage, object detections, depth summary) and produces a single
 * final_action + final_text for TTS.
 *
 * The decision cascade mirrors the reference cloud `audio_cue_text` priority order (see
 * app_dev_nav.py), MINUS the motion/speed level which is unavailable on device:
 *   1. close person in the center path        -> CAUTION
 *   2. any object very close in the center     -> STOP
 *   2b. local fire/smoke                       -> STOP
 *   3. object 1-2 m in the center              -> CAUTION
 *   4. path blocked / not walkable             -> WAIT
 *   5. hazard within ~3 m                      -> CAUTION / SLOW
 *   6. narrow clear path                       -> SLOW
 *   7. clear                                   -> WALK / bear / TURN
 *
 * Works with an empty cloudData map (cloud unreachable) — it then reasons from local data only.
 * It never depends on motion/speed (defaults: dynamic=false, vel=0).
 */
object AndroidNavigationModule {

    private val PERSON_LABELS = setOf("person", "pedestrian", "people")
    private const val LOCAL_EVIDENCE_WINDOW_MS = 6_000L
    private const val LOCAL_HAZARD_CONFIRMATIONS_REQUIRED = 2

    private data class LocalEvidenceKey(val feature: Feature, val label: String)
    private data class LocalEvidence(
        var confirmations: Int,
        var lastFrameId: Long,
        var lastSeenMs: Long
    )

    private val localEvidence = mutableMapOf<LocalEvidenceKey, LocalEvidence>()

    fun evaluate(
        frameId: String?,
        seq: Int?,
        localResult: HazardFrameResult?,
        cloudData: Map<String, Any?>
    ): NavigationDecision? {
        val resolvedFrameId = frameId ?: "frame-${System.currentTimeMillis()}"
        val normalizedCloudData = normalizeCloudData(cloudData)
        val scene = resolveSceneType(localResult)
        val sceneName = resolveSceneName(localResult)
        val depth = extractDepth(normalizedCloudData)
        val segmentation = extractSegmentationCoverage(normalizedCloudData)
        val cloudObjects = extractCloudObjects(normalizedCloudData)
        val cloudHazards = extractCloudHazards(normalizedCloudData)
        val localHazards = extractLocalHazards(localResult)
        val localPeople = extractLocalPedestrians(localResult)

        val agents = (cloudObjects + localPeople)
            .distinctBy { it.label + it.zone + (it.distanceM ?: -1.0) }
        val hazards = localHazards + cloudHazards

        // footpath / floor are the ideal walkable classes; road counts at half weight.
        val walkableCoverage = max(segmentation["footpath"] ?: 0.0, segmentation["floor"] ?: 0.0) +
            (segmentation["road"] ?: 0.0) * 0.5

        val d = decide(localResult, scene, agents, hazards, depth, segmentation, walkableCoverage)

        val source = linkedMapOf(
            "scene" to "android",
            "segmentation" to if (segmentation.isEmpty()) "unavailable" else "cloud_gpu2",
            "object_detection" to if (cloudObjects.isEmpty()) "android_local_only" else "cloud_gpu2",
            "depth" to if (depth.available) "cloud_gpu3" else "unavailable",
            "hazards" to "android"
        )

        val debugSummary = buildString {
            append("scene=").append(scene)
            if (sceneName != "unknown") append(" (").append(sceneName).append(")")
            append(" | depth=").append(depth.nearestObstacleM?.let { "%.1fm".format(it) } ?: "n/a")
            append(" | clear=").append(depth.centerPathClearM?.let { "%.1fm".format(it) } ?: "n/a")
            append(" | walk=").append("%.0f".format(walkableCoverage))
            append(" | obj=").append(agents.size)
            append(" | hz=").append(hazards.size)
            append(" | ").append(d.action)
        }

        return NavigationDecision(
            frameId = resolvedFrameId,
            seq = seq,
            finalAction = d.action,
            finalText = d.text,
            direction = d.direction,
            severity = d.severity,
            reason = d.reason,
            shouldSpeak = true,
            source = source,
            debugSummary = debugSummary
        )
    }

    private fun decide(
        local: HazardFrameResult?,
        scene: String,
        agents: List<NavObject>,
        hazards: List<NavHazard>,
        depth: DepthSummary,
        segmentation: Map<String, Double>,
        walkableCoverage: Double
    ): Quint {
        // 0. Night gate (local).
        if (local?.dayNight == DayNight.NIGHT) {
            return Quint("WAIT", "Low light detected. Please wait; navigation guidance is limited.",
                "hold", "high", "Local day-night gate marked the scene as night.")
        }

        val sorted = agents.sortedBy { it.distanceM ?: 99.0 }
        val persons = sorted.filter { it.label.lowercase() in PERSON_LABELS }

        // 1. Close (or unconfirmed) person in the center path -> CAUTION.
        persons.firstOrNull { it.zone == "center" && near(it, 2.0, depth, includeUnknown = true) }?.let { p ->
            val dist = effDist(p, depth)
            return Quint("CAUTION", "Caution, person ahead${meters(dist)}.", "slow", "medium",
                "A person is in the center of the path.")
        }

        // 2. Any object very close in the center -> STOP.
        sorted.firstOrNull { it.zone == "center" && near(it, 1.0, depth, includeUnknown = false) }?.let { a ->
            val dist = effDist(a, depth)
            return Quint("STOP", "Stop. ${cap(a.label)} ahead${meters(dist)}.", "hold", "critical",
                "An object is very close in the center path.")
        }
        if (depth.nearestObstacleM != null && depth.nearestObstacleM <= 1.0) {
            return Quint("STOP", "Stop. Obstacle very close ahead.", "hold", "critical",
                "Depth reported a near obstacle directly ahead.")
        }

        // 2b. Local fire/smoke is always urgent regardless of distance.
        hazards.firstOrNull { it.type.contains("fire") || it.type.contains("smoke") }?.let { hz ->
            return Quint("STOP", "Fire or smoke detected ahead. Stop and move away carefully.",
                avoidDirectionForZone(hz.zone), "critical", "Local fire or smoke detector reported a hazard.")
        }

        // 3. Object 1-2 m in the center -> CAUTION.
        sorted.firstOrNull {
            val dist = effDist(it, depth)
            it.zone == "center" && dist != null && dist > 1.0 && dist <= 2.0
        }?.let { a ->
            val dist = effDist(a, depth)
            return Quint("CAUTION", "Caution, ${a.label} ahead${meters(dist)}.", "slow", "medium",
                "An object is ahead in the center path.")
        }

        // 4. Path blocked / not enough walkable area -> WAIT.
        val centerClear = depth.centerPathClearM
        val blocked = (centerClear != null && centerClear < 1.5) ||
            (segmentation.isNotEmpty() && walkableCoverage <= 8.0 && (centerClear == null || centerClear < 2.5))
        if (blocked) {
            hazards.filter { it.distanceM != null }.minByOrNull { it.distanceM!! }
                ?.takeIf { (it.distanceM ?: 99.0) <= 2.5 }?.let { hz ->
                    return Quint("WAIT", "${cap(hz.type.replace('_', ' '))} blocking the path${meters(hz.distanceM)}. Stop and wait.",
                        "hold", "high", "A hazard is blocking the path.")
                }
            sorted.firstOrNull { (it.distanceM ?: 99.0) <= 3.0 }?.let { a ->
                return Quint("WAIT", "${cap(a.label)} blocking the path${meters(a.distanceM)}. Stop and wait.",
                    "hold", "high", "An object is blocking the path.")
            }
            return Quint("WAIT", "Path ahead is blocked. Stop and wait.", "hold", "high",
                "Segmentation/depth show no clear walkable area ahead.")
        }

        // 5. Hazard within ~3 m -> CAUTION / SLOW. Local TFLite hazards do not have depth, so do
        // not invent a left/right detour from an unknown distance; give careful forward guidance.
        hazards.sortedBy { it.distanceM ?: 99.0 }.firstOrNull {
            it.distanceM != null && it.distanceM <= 3.0
        }?.let { hz ->
            val detour = avoidDirectionForZone(hz.zone)
            val action = if (hz.type.contains("pothole") || hz.type.contains("wet")) "CAUTION" else "SLOW"
            return Quint(action, "${cap(hz.type.replace('_', ' '))} ahead${meters(hz.distanceM)}. Move $detour carefully.",
                detour, "medium", "A path hazard is near the route.")
        }
        hazards.firstOrNull { it.distanceM == null }?.let { hz ->
            val action = if (hz.type.contains("pothole") || hz.type.contains("wet")) "CAUTION" else "SLOW"
            return Quint(action, "${cap(hz.type.replace('_', ' '))} detected ahead. Slow down and continue carefully.",
                "ahead", "medium", "A local hazard was detected without reliable distance.")
        }

        // 6. Narrow clear path -> SLOW.
        if (centerClear != null && centerClear < 1.8) {
            return Quint("SLOW", "Path ahead is narrow. Slow down and continue carefully.",
                "ahead", "medium", "Depth reported a limited clear path width.")
        }

        // 7. Clear -> WALK (or gentle bear to the clearer side).
        return when (clearerSide(sorted, hazards)) {
            "left" -> Quint("WALK", "Bear left and continue forward.", "left", "none",
                "Center has minor obstacles; the left side is clearer.")
            "right" -> Quint("WALK", "Bear right and continue forward.", "right", "none",
                "Center has minor obstacles; the right side is clearer.")
            else -> Quint(
                "WALK",
                if (scene == "indoor") "Continue straight. The indoor path ahead is clear."
                else "Continue straight. The path ahead is clear.",
                "ahead", "none", "Walkable area available and no close obstacle in the center."
            )
        }
    }

    /** Pick the clearer side only when the center actually has something; otherwise go straight. */
    private fun clearerSide(agents: List<NavObject>, hazards: List<NavHazard>): String {
        val centerBusy = agents.any { it.zone == "center" } || hazards.any { it.zone == "center" }
        if (!centerBusy) return "center"
        val leftCount = agents.count { it.zone == "left" } + hazards.count { it.zone == "left" }
        val rightCount = agents.count { it.zone == "right" } + hazards.count { it.zone == "right" }
        return if (leftCount <= rightCount) "left" else "right"
    }

    /** Effective distance: the detection's own distance, or the global nearest obstacle for center items. */
    private fun effDist(obj: NavObject, depth: DepthSummary): Double? =
        obj.distanceM ?: if (obj.zone == "center") depth.nearestObstacleM else null

    private fun near(obj: NavObject, thresholdM: Double, depth: DepthSummary, includeUnknown: Boolean): Boolean {
        val dist = effDist(obj, depth)
        return if (dist == null) includeUnknown else dist <= thresholdM
    }

    private fun meters(d: Double?): String = if (d != null) ", ${"%.1f".format(d)} metres" else ""

    private fun cap(s: String): String = s.replaceFirstChar(Char::titlecase)

    private data class Quint(
        val action: String, val text: String, val direction: String,
        val severity: String, val reason: String
    )

    private data class DepthSummary(
        val available: Boolean,
        val nearestObstacleM: Double?,
        val centerPathClearM: Double?
    )

    private data class NavObject(val label: String, val confidence: Double, val zone: String, val distanceM: Double?)

    private data class NavHazard(
        val type: String, val confidence: Double, val zone: String,
        val distanceM: Double?, val source: String
    )

    private fun resolveSceneType(localResult: HazardFrameResult?): String {
        return when (localResult?.sceneType ?: SceneType.UNKNOWN) {
            SceneType.INDOOR -> "indoor"
            SceneType.OUTDOOR -> "outdoor"
            SceneType.UNKNOWN -> "outdoor"
        }
    }

    private fun resolveSceneName(localResult: HazardFrameResult?): String {
        val top = localResult?.detectionsByFeature?.get(Feature.SCENE)?.firstOrNull()?.label ?: return "unknown"
        return top.substringBeforeLast("_").replace('_', ' ')
    }

    private fun extractDepth(root: Map<String, Any?>): DepthSummary {
        val depth = root.mapPath("gpu3_response").mapPath("depth_result")
        return DepthSummary(
            available = depth.bool("success") ?: false,
            nearestObstacleM = depth.double("nearest_obstacle_m"),
            centerPathClearM = depth.double("center_path_clear_m")
        )
    }

    private fun extractSegmentationCoverage(root: Map<String, Any?>): Map<String, Double> {
        val features = root.mapPath("gpu2_results")["features"] as? List<*> ?: return emptyMap()
        for (feature in features) {
            val map = feature as? Map<*, *> ?: continue
            if (map["feature_name"]?.toString()?.lowercase()?.contains("segmentation") != true) continue
            val coverage = ((map["result"] as? Map<*, *>)?.get("stats") as? Map<*, *>)?.get("coverage_pct") as? Map<*, *>
                ?: continue
            return coverage.entries.associate { it.key.toString().lowercase() to (it.value as? Number)?.toDouble().orZero() }
        }
        return emptyMap()
    }

    private fun normalizeCloudData(root: Map<String, Any?>): Map<String, Any?> {
        val data = root.mapPath("data")
        val candidates = listOf(
            root,
            data,
            root.mapPath("cloud"),
            data.mapPath("cloud")
        )
        return candidates.firstOrNull { it.hasCloudSignals() } ?: root
    }

    private fun Map<String, Any?>.hasCloudSignals(): Boolean =
        containsKey("gpu2_results") || containsKey("gpu3_response")

    private fun extractCloudObjects(root: Map<String, Any?>): List<NavObject> {
        val features = root.mapPath("gpu2_results")["features"] as? List<*> ?: return emptyList()
        val objects = mutableListOf<NavObject>()
        for (feature in features) {
            val map = feature as? Map<*, *> ?: continue
            if (map["feature_name"]?.toString()?.lowercase()?.contains("object") != true) continue
            val detections = (map["result"] as? Map<*, *>)?.get("detections") as? List<*> ?: continue
            detections.forEach { item ->
                val det = item as? Map<*, *> ?: return@forEach
                val label = det["label"]?.toString() ?: det["class_name"]?.toString() ?: return@forEach
                val confidence = (det["confidence"] as? Number)?.toDouble().orZero()
                val zone = (det["bbox"] as? Map<*, *>)?.zone() ?: det["zone"]?.toString() ?: "center"
                val distance = det.distanceMetres()
                objects += NavObject(label, confidence, zone, distance)
            }
        }
        return objects
    }

    /** Cloud context hazards (gpu3_response.context_result.hazards), if present. */
    private fun extractCloudHazards(root: Map<String, Any?>): List<NavHazard> {
        val hazards = root.mapPath("gpu3_response").mapPath("context_result")["hazards"] as? List<*>
            ?: return emptyList()
        return hazards.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val type = map["type"]?.toString() ?: map["label"]?.toString() ?: return@mapNotNull null
            NavHazard(
                type = type,
                confidence = (map["confidence"] as? Number)?.toDouble().orZero(),
                zone = (map["bbox"] as? Map<*, *>)?.zone() ?: map["zone"]?.toString() ?: "center",
                distanceM = map.distanceMetres(),
                source = "cloud_context"
            )
        }
    }

    private fun extractLocalHazards(localResult: HazardFrameResult?): List<NavHazard> {
        if (localResult == null) return emptyList()
        val hazardFeatures = listOf(Feature.FIRE_SMOKE, Feature.WET_DRY, Feature.POTHOLE, Feature.ELECTRIC_POLE)
        return hazardFeatures.flatMap { feature ->
            if (!localResult.isFreshLocalRun(feature)) return@flatMap emptyList()
            localResult.detectionsByFeature[feature].orEmpty().mapNotNull { detection ->
                val zone = detection.zone()
                if (!isConfirmedLocalHazard(localResult, feature, detection)) return@mapNotNull null
                NavHazard(
                    type = detection.label.ifBlank { feature.name.lowercase() },
                    confidence = detection.score.toDouble(),
                    zone = zone,
                    distanceM = null,
                    source = "android_tflite"
                )
            }
        }
    }

    private fun extractLocalPedestrians(localResult: HazardFrameResult?): List<NavObject> {
        if (localResult == null) return emptyList()
        if (!localResult.isFreshLocalRun(Feature.PEDESTRIAN)) return emptyList()
        return localResult.detectionsByFeature[Feature.PEDESTRIAN].orEmpty().map { detection ->
            NavObject(
                label = detection.label.ifBlank { "person" },
                confidence = detection.score.toDouble(),
                zone = detection.zone(),
                distanceM = null
            )
        }
    }

    @Synchronized
    private fun isConfirmedLocalHazard(
        result: HazardFrameResult,
        feature: Feature,
        detection: Detection
    ): Boolean {
        val now = result.tsMs
        localEvidence.entries.removeAll { now - it.value.lastSeenMs > LOCAL_EVIDENCE_WINDOW_MS }

        val key = LocalEvidenceKey(
            feature = feature,
            label = detection.label.ifBlank { feature.name.lowercase() }.lowercase()
        )
        val evidence = localEvidence[key]
        val updated = when {
            evidence == null -> LocalEvidence(1, result.frameId, now)
            evidence.lastFrameId == result.frameId -> evidence
            now - evidence.lastSeenMs > LOCAL_EVIDENCE_WINDOW_MS -> {
                evidence.confirmations = 1
                evidence.lastFrameId = result.frameId
                evidence.lastSeenMs = now
                evidence
            }
            else -> {
                evidence.confirmations += 1
                evidence.lastFrameId = result.frameId
                evidence.lastSeenMs = now
                evidence
            }
        }
        localEvidence[key] = updated
        return updated.confirmations >= LOCAL_HAZARD_CONFIRMATIONS_REQUIRED
    }

    private fun HazardFrameResult.isFreshLocalRun(feature: Feature): Boolean {
        return executedFeatures.contains(feature) && (latenciesMs[feature] ?: 0L) > 0L
    }

    internal fun resetLocalEvidenceForTests() {
        synchronized(this) {
            localEvidence.clear()
        }
    }

    private fun Detection.zone(): String {
        val box = boxLtrb ?: return "center"
        val centerX = (box[0] + box[2]) / 2f
        return when {
            centerX < 0.33f -> "left"
            centerX > 0.66f -> "right"
            else -> "center"
        }
    }

    private fun Map<*, *>.zone(): String {
        val normalized = this["normalized"] as? Boolean ?: false
        val width = (this["image_width"] as? Number)?.toDouble()
            ?: (this["width_px"] as? Number)?.toDouble()
            ?: 640.0
        val x1 = (this["x1"] as? Number)?.toDouble()
            ?: (this["left"] as? Number)?.toDouble()
            ?: (this["x"] as? Number)?.toDouble()
            ?: (this["cx"] as? Number)?.toDouble()?.let { cx ->
                val w = (this["w"] as? Number)?.toDouble() ?: (this["width"] as? Number)?.toDouble() ?: 0.0
                cx - (w / 2.0)
            }
            ?: return "center"
        val x2 = (this["x2"] as? Number)?.toDouble()
            ?: (this["right"] as? Number)?.toDouble()
            ?: run {
                val w = (this["w"] as? Number)?.toDouble() ?: (this["width"] as? Number)?.toDouble()
                if (w != null) x1 + w else null
            }
            ?: return "center"
        val denominator = if (normalized) 1.0 else width
        val center = ((x1 + x2) / 2.0) / denominator
        return when {
            center < 0.33 -> "left"
            center > 0.66 -> "right"
            else -> "center"
        }
    }

    private fun Map<*, *>.distanceMetres(): Double? {
        val keys = listOf("dist_m", "distance_m", "depth_m", "distance", "nearest_obstacle_m")
        return keys.firstNotNullOfOrNull { key ->
            when (val value = this[key]) {
                is Number -> value.toDouble()
                is String -> value.toDoubleOrNull()
                else -> null
            }
        }
    }

    private fun avoidDirectionForZone(zone: String): String = when (zone) {
        "left" -> "right"
        "right" -> "left"
        else -> "ahead"
    }

    private fun Map<String, Any?>.mapPath(key: String): Map<String, Any?> =
        (this[key] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: emptyMap()

    private fun Map<String, Any?>.double(key: String): Double? = (this[key] as? Number)?.toDouble()
    private fun Map<String, Any?>.bool(key: String): Boolean? = this[key] as? Boolean
    private fun Number?.orZero(): Double = this?.toDouble() ?: 0.0
}
