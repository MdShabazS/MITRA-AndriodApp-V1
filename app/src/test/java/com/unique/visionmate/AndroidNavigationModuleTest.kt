package com.unique.visionmate

import com.unique.visionmate.engine.DayNight
import com.unique.visionmate.engine.Detection
import com.unique.visionmate.engine.Feature
import com.unique.visionmate.engine.HazardFrameResult
import com.unique.visionmate.engine.SceneType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidNavigationModuleTest {

    @Test
    fun evaluate_nightFrame_waitsWithLimitedGuidance() {
        val decision = AndroidNavigationModule.evaluate(
            frameId = "frame-night",
            seq = 11,
            localResult = result(dayNight = DayNight.NIGHT),
            cloudData = emptyMap()
        )!!

        assertEquals("WAIT", decision.finalAction)
        assertEquals("high", decision.severity)
        assertEquals(11, decision.seq)
        assertTrue(decision.finalText.contains("Low light"))
    }

    @Test
    fun evaluate_localFireSmoke_stopsImmediately() {
        val decision = AndroidNavigationModule.evaluate(
            frameId = "frame-fire",
            seq = 12,
            localResult = result(
                detections = mapOf(
                    Feature.FIRE_SMOKE to listOf(
                        Detection("smoke", 0.91f, floatArrayOf(0.35f, 0.2f, 0.65f, 0.8f))
                    )
                )
            ),
            cloudData = emptyMap()
        )!!

        assertEquals("STOP", decision.finalAction)
        assertEquals("critical", decision.severity)
        assertTrue(decision.finalText.contains("Fire or smoke"))
    }

    @Test
    fun evaluate_closeDepthObstacle_stops() {
        val decision = AndroidNavigationModule.evaluate(
            frameId = "frame-depth",
            seq = 13,
            localResult = result(),
            cloudData = mapOf(
                "gpu3_response" to mapOf(
                    "depth_result" to mapOf(
                        "success" to true,
                        "nearest_obstacle_m" to 0.8,
                        "center_path_clear_m" to 0.8
                    )
                )
            )
        )!!

        assertEquals("STOP", decision.finalAction)
        assertEquals("critical", decision.severity)
        assertTrue(decision.debugSummary.contains("depth=0.8m"))
        assertEquals("cloud_gpu3", decision.source["depth"])
    }

    @Test
    fun evaluate_cloudObjectWithDistanceAlias_stopsWhenCloseInCenter() {
        val decision = AndroidNavigationModule.evaluate(
            frameId = "frame-object-distance-alias",
            seq = 17,
            localResult = result(),
            cloudData = mapOf(
                "gpu2_results" to mapOf(
                    "features" to listOf(
                        mapOf(
                            "feature_name" to "Object Detection",
                            "result" to mapOf(
                                "detections" to listOf(
                                    mapOf(
                                        "label" to "chair",
                                        "confidence" to 0.88,
                                        "distance_m" to "0.7",
                                        "bbox" to mapOf(
                                            "x" to 250,
                                            "width" to 140,
                                            "image_width" to 640
                                        )
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )!!

        assertEquals("STOP", decision.finalAction)
        assertEquals("critical", decision.severity)
        assertTrue(decision.finalText.contains("Chair ahead"))
    }

    @Test
    fun evaluate_blockedPathWithCloudHazardDepthAlias_waits() {
        val decision = AndroidNavigationModule.evaluate(
            frameId = "frame-cloud-hazard",
            seq = 18,
            localResult = result(),
            cloudData = mapOf(
                "gpu2_results" to mapOf(
                    "features" to listOf(
                        mapOf(
                            "feature_name" to "Outdoor Segmentation",
                            "result" to mapOf(
                                "stats" to mapOf(
                                    "coverage_pct" to mapOf("footpath" to 3.0, "road" to 2.0)
                                )
                            )
                        )
                    )
                ),
                "gpu3_response" to mapOf(
                    "depth_result" to mapOf(
                        "success" to true,
                        "nearest_obstacle_m" to 2.2,
                        "center_path_clear_m" to 1.1
                    ),
                    "context_result" to mapOf(
                        "hazards" to listOf(
                            mapOf(
                                "type" to "pothole",
                                "confidence" to 0.9,
                                "depth_m" to 2.1,
                                "zone" to "center"
                            )
                        )
                    )
                )
            )
        )!!

        assertEquals("WAIT", decision.finalAction)
        assertEquals("high", decision.severity)
        assertTrue(decision.finalText.contains("Pothole blocking"))
    }

    @Test
    fun evaluate_wrappedCloudData_readsDepthMetadata() {
        val decision = AndroidNavigationModule.evaluate(
            frameId = "frame-wrapped",
            seq = 15,
            localResult = result(),
            cloudData = mapOf(
                "data" to mapOf(
                    "cloud" to mapOf(
                        "gpu3_response" to mapOf(
                            "depth_result" to mapOf(
                                "success" to true,
                                "nearest_obstacle_m" to 0.9,
                                "center_path_clear_m" to 0.7
                            )
                        )
                    )
                )
            )
        )!!

        assertEquals("STOP", decision.finalAction)
        assertEquals("cloud_gpu3", decision.source["depth"])
        assertTrue(decision.debugSummary.contains("depth=0.9m"))
    }

    @Test
    fun evaluate_clearIndoorPath_walksWithIndoorText() {
        val decision = AndroidNavigationModule.evaluate(
            frameId = "frame-clear",
            seq = 14,
            localResult = result(sceneType = SceneType.INDOOR),
            cloudData = mapOf(
                "gpu2_results" to mapOf(
                    "features" to listOf(
                        mapOf(
                            "feature_name" to "Indoor Segmentation",
                            "result" to mapOf(
                                "stats" to mapOf(
                                    "coverage_pct" to mapOf("floor" to 45.0)
                                )
                            )
                        )
                    )
                ),
                "gpu3_response" to mapOf(
                    "depth_result" to mapOf(
                        "success" to true,
                        "nearest_obstacle_m" to 4.0,
                        "center_path_clear_m" to 3.0
                    )
                )
            )
        )!!

        assertEquals("WALK", decision.finalAction)
        assertEquals("none", decision.severity)
        assertTrue(decision.finalText.contains("indoor path"))
        assertEquals("cloud_gpu2", decision.source["segmentation"])
    }

    @Test
    fun evaluate_localWetWithoutDepth_doesNotInventRightTurn() {
        val decision = AndroidNavigationModule.evaluate(
            frameId = "frame-wet",
            seq = 16,
            localResult = result(
                detections = mapOf(
                    Feature.WET_DRY to listOf(
                        Detection("wet", 0.80f, floatArrayOf(0.35f, 0.55f, 0.65f, 0.95f))
                    )
                )
            ),
            cloudData = emptyMap()
        )!!

        assertEquals("CAUTION", decision.finalAction)
        assertEquals("ahead", decision.direction)
        assertTrue(decision.finalText.contains("Slow down"))
        assertTrue(!decision.finalText.contains("right", ignoreCase = true))
    }

    private fun result(
        dayNight: DayNight = DayNight.DAY,
        sceneType: SceneType = SceneType.OUTDOOR,
        detections: Map<Feature, List<Detection>> = emptyMap()
    ): HazardFrameResult = HazardFrameResult(
        frameId = 1,
        tsMs = 100,
        dayNight = dayNight,
        sceneType = sceneType,
        executedFeatures = detections.keys,
        skippedFeatures = emptyMap(),
        detectionsByFeature = detections,
        latenciesMs = emptyMap(),
        skipReason = null
    )
}
