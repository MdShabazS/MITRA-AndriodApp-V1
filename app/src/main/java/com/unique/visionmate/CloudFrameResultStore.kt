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

data class CloudFrameTelemetry(
    val status: String,
    val framesSent: Long,
    val repliesReceived: Long,
    val ackTimeouts: Long,
    val lastSentSeq: Int?,
    val lastAckSeq: Int?,
    val lastSentAtMs: Long,
    val lastAckAtMs: Long,
    val lastReplyAtMs: Long,
    val lastReplyType: String?,
    val lastReplyFrameId: String?,
    val lastOutputText: String?
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
    private val telemetryLock = Any()
    private var status: String = "idle"
    private var framesSent: Long = 0L
    private var repliesReceived: Long = 0L
    private var ackTimeouts: Long = 0L
    private var lastSentSeq: Int? = null
    private var lastAckSeq: Int? = null
    private var lastSentAtMs: Long = 0L
    private var lastAckAtMs: Long = 0L
    private var lastReplyAtMs: Long = 0L
    private var lastReplyType: String? = null
    private var lastReplyFrameId: String? = null
    private var lastOutputText: String? = null

    fun update(result: CloudFrameResult) {
        latest = result
        synchronized(telemetryLock) {
            repliesReceived++
            lastReplyAtMs = SystemClock.elapsedRealtime()
            lastReplyType = result.responseType
            lastReplyFrameId = result.frameId
            lastOutputText = result.outputText
            status = "reply-received"
        }
    }

    fun latest(): CloudFrameResult? = latest

    fun resetTelemetry(newStatus: String = "idle") {
        synchronized(telemetryLock) {
            status = newStatus
            framesSent = 0L
            repliesReceived = 0L
            ackTimeouts = 0L
            lastSentSeq = null
            lastAckSeq = null
            lastSentAtMs = 0L
            lastAckAtMs = 0L
            lastReplyAtMs = 0L
            lastReplyType = null
            lastReplyFrameId = null
            lastOutputText = null
        }
        latest = null
    }

    fun noteStatus(newStatus: String) {
        synchronized(telemetryLock) {
            status = newStatus
        }
    }

    fun noteFrameSent(seq: Int) {
        synchronized(telemetryLock) {
            framesSent++
            lastSentSeq = seq
            lastSentAtMs = SystemClock.elapsedRealtime()
            status = "frame-sent"
        }
    }

    fun noteFrameSendFailed() {
        synchronized(telemetryLock) {
            status = "send-failed"
        }
    }

    fun noteAck(seq: Int?) {
        synchronized(telemetryLock) {
            lastAckSeq = seq ?: lastAckSeq
            lastAckAtMs = SystemClock.elapsedRealtime()
            status = "ack-received"
        }
    }

    fun noteAckTimeout() {
        synchronized(telemetryLock) {
            ackTimeouts++
            status = "ack-timeout"
        }
    }

    fun telemetry(): CloudFrameTelemetry {
        synchronized(telemetryLock) {
            return CloudFrameTelemetry(
                status = status,
                framesSent = framesSent,
                repliesReceived = repliesReceived,
                ackTimeouts = ackTimeouts,
                lastSentSeq = lastSentSeq,
                lastAckSeq = lastAckSeq,
                lastSentAtMs = lastSentAtMs,
                lastAckAtMs = lastAckAtMs,
                lastReplyAtMs = lastReplyAtMs,
                lastReplyType = lastReplyType,
                lastReplyFrameId = lastReplyFrameId,
                lastOutputText = lastOutputText
            )
        }
    }

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
