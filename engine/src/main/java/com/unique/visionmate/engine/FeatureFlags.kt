package com.unique.visionmate.engine

import android.content.SharedPreferences

internal class FeatureFlags(private val prefs: SharedPreferences) {

    fun isEnabled(feature: Feature): Boolean = prefs.getBoolean(keyFor(feature), true)

    private fun keyFor(feature: Feature): String = "offload." + when (feature) {
        Feature.DAY_NIGHT -> "daynight"
        Feature.SCENE -> "scene"
        Feature.FIRE_SMOKE -> "firesmoke"
        Feature.WET_DRY -> "wetdry"
        Feature.OCR -> "ocr"
        Feature.PEDESTRIAN -> "pedestrian"
        Feature.POTHOLE -> "pothole"
        Feature.ELECTRIC_POLE -> "electricpole"
    } + ".enabled"

    companion object {
        const val GLOBAL_KEY = "offload.enabled"
        fun isGlobalEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(GLOBAL_KEY, false)
    }
}
