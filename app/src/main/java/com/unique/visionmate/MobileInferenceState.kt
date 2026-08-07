package com.unique.visionmate

import android.os.SystemClock
import com.unique.visionmate.engine.HazardFrameResult

object MobileInferenceState {

    @Volatile
    private var latestResult: HazardFrameResult? = null

    fun update(result: HazardFrameResult) {
        latestResult = result
    }

    fun latest(): HazardFrameResult? = latestResult

    fun latestFresh(maxAgeMs: Long): HazardFrameResult? {
        val result = latestResult ?: return null
        val ageMs = SystemClock.elapsedRealtime() - result.tsMs
        return if (ageMs in 0..maxAgeMs) result else null
    }
}
