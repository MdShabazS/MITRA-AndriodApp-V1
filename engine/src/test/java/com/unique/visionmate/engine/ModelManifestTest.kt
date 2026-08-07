package com.unique.visionmate.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelManifestTest {

    @Test
    fun parse_validManifest_readsFeatureSpec() {
        val specs = ModelManifest.parse(
            """
            {
              "features": {
                "FIRE_SMOKE": {
                  "enabled": true,
                  "asset": "models/fire_smoke.tflite",
                  "inputWidth": 320,
                  "inputHeight": 192,
                  "mean": [1.0, 2.0, 3.0],
                  "std": [255.0, 254.0, 253.0],
                  "labels": ["fire", "smoke"],
                  "postprocessor": "DETECTION_NMS",
                  "confidenceThreshold": 0.42
                }
              }
            }
            """.trimIndent()
        )

        val spec = specs[Feature.FIRE_SMOKE]!!
        assertTrue(spec.enabled)
        assertEquals("models/fire_smoke.tflite", spec.assetPath)
        assertEquals(320, spec.inputWidth)
        assertEquals(192, spec.inputHeight)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f), spec.mean, 0.001f)
        assertArrayEquals(floatArrayOf(255f, 254f, 253f), spec.std, 0.001f)
        assertEquals(listOf("fire", "smoke"), spec.labels)
        assertEquals("DETECTION_NMS", spec.postprocessor)
        assertEquals(0.42f, spec.confidenceThreshold, 0.001f)
    }

    @Test
    fun parse_malformedManifest_disablesAllFeatures() {
        assertTrue(ModelManifest.parse("{ not-json").isEmpty())
    }

    @Test
    fun parse_unknownFeatureKey_isIgnored() {
        val specs = ModelManifest.parse(
            """
            {
              "features": {
                "UNKNOWN_FEATURE": { "enabled": true, "asset": "models/x.tflite" },
                "SCENE": { "enabled": true, "asset": "models/scene.tflite" }
              }
            }
            """.trimIndent()
        )

        assertEquals(setOf(Feature.SCENE), specs.keys)
    }

    @Test
    fun parse_disabledFeature_remainsPresentButDisabled() {
        val specs = ModelManifest.parse(
            """
            { "features": { "POTHOLE": { "enabled": false, "asset": "models/pothole.tflite" } } }
            """.trimIndent()
        )

        val spec = specs[Feature.POTHOLE]!!
        assertFalse(spec.enabled)
        assertEquals("models/pothole.tflite", spec.assetPath)
    }

    @Test
    fun parse_missingAssetPath_setsNullAssetPath() {
        val specs = ModelManifest.parse(
            """
            { "features": { "ELECTRIC_POLE": { "enabled": true } } }
            """.trimIndent()
        )

        assertNull(specs[Feature.ELECTRIC_POLE]!!.assetPath)
    }

    @Test
    fun parse_missingMeanStd_useSafeDefaults() {
        val specs = ModelManifest.parse(
            """
            { "features": { "WET_DRY": { "enabled": true, "asset": "models/wet_dry.tflite" } } }
            """.trimIndent()
        )

        val spec = specs[Feature.WET_DRY]!!
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), spec.mean, 0.001f)
        assertArrayEquals(floatArrayOf(255f, 255f, 255f), spec.std, 0.001f)
        assertEquals(0.25f, spec.confidenceThreshold, 0.001f)
    }

    @Test
    fun parse_mismatchedMeanStd_useSafeDefaults() {
        val specs = ModelManifest.parse(
            """
            {
              "features": {
                "PEDESTRIAN": {
                  "enabled": true,
                  "asset": "models/pedestrian.tflite",
                  "mean": [127.5],
                  "std": [0.0, 255.0, 255.0, 12.0]
                }
              }
            }
            """.trimIndent()
        )

        val spec = specs[Feature.PEDESTRIAN]!!
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), spec.mean, 0.001f)
        assertArrayEquals(floatArrayOf(255f, 255f, 255f), spec.std, 0.001f)
    }

    @Test
    fun parse_malformedSingleArrays_doNotDropFeature() {
        val specs = ModelManifest.parse(
            """
            {
              "features": {
                "SCENE": {
                  "enabled": true,
                  "asset": "models/scene.tflite",
                  "mean": ["bad", 1.0, 2.0],
                  "std": [255.0, "bad", 255.0],
                  "labels": ["indoor", 7]
                },
                "FIRE_SMOKE": {
                  "enabled": true,
                  "asset": "models/fire_smoke.tflite",
                  "labels": ["fire"]
                }
              }
            }
            """.trimIndent()
        )

        assertTrue(specs[Feature.SCENE]!!.enabled)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), specs[Feature.SCENE]!!.mean, 0.001f)
        assertArrayEquals(floatArrayOf(255f, 255f, 255f), specs[Feature.SCENE]!!.std, 0.001f)
        assertEquals(listOf("fire"), specs[Feature.FIRE_SMOKE]!!.labels)
    }
}
