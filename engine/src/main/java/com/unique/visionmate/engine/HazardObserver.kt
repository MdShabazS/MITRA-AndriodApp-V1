package com.unique.visionmate.engine

interface HazardObserver {
    fun onResult(result: HazardFrameResult)
    fun onTelemetry(event: TelemetryEvent) {}
}

sealed class TelemetryEvent {
    data class FrameSkippedNight(val frameId: Long) : TelemetryEvent()
    data class FeatureTimeout(val feature: Feature, val frameId: Long) : TelemetryEvent()
    data class FeatureDisabled(val feature: Feature, val reason: SkipReason) : TelemetryEvent()
    data class QueueDropStale(val frameId: Long, val ageMs: Long) : TelemetryEvent()
    data class FeatureException(val feature: Feature, val message: String) : TelemetryEvent()
}
