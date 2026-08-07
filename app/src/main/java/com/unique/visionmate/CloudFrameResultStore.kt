package com.unique.visionmate

import android.os.SystemClock

data class CloudFrameResult(
    val frameId: String?,
    val responseType: String?,
    val outputText: String?,
    val action: String?,
    val outer: Map<String, Any?>,
    val inner: Map<String, Any?>,
    val navigation: NavigationDecision? = null
)

object CloudFrameResultStore {

    @Volatile
    private var latest: CloudFrameResult? = null

    @Volatile
    private var lastSpokenText: String? = null
    @Volatile
    private var lastSpokenAtMs: Long = 0L

    // Min spacing between cloud captions so they don't run back-to-back.
    private const val CAPTION_MIN_GAP_MS = 8_000L

    fun update(result: CloudFrameResult) {
        latest = result
    }

    fun latest(): CloudFrameResult? = latest

    /** Dedupe on the text (NOT frame_id, which changes every frame) plus a minimum spacing. */
    fun shouldSpeak(frameId: String?, outputText: String?): Boolean {
        val text = outputText?.trim().orEmpty()
        if (text.isBlank()) return false
        val now = SystemClock.elapsedRealtime()
        // Min spacing between any captions, and never repeat the same caption within that window.
        if (now - lastSpokenAtMs < CAPTION_MIN_GAP_MS) return false
        lastSpokenText = text
        lastSpokenAtMs = now
        return true
    }
}
