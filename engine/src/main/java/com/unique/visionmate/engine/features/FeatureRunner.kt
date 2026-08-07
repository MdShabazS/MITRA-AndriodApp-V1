package com.unique.visionmate.engine.features

import android.graphics.Bitmap
import com.unique.visionmate.engine.Detection
import com.unique.visionmate.engine.Feature

internal interface FeatureRunner {
    val feature: Feature
    val enabled: Boolean
    suspend fun run(bitmap: Bitmap): List<Detection>
    fun close() {}
}
