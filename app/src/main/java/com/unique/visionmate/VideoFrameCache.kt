package com.unique.visionmate

import android.graphics.Bitmap
import android.os.SystemClock

/**
 * Single canonical latest-frame publisher (the "frame bus").
 *
 * Producer: [RtspFrameSource] only — it decodes RTSP once and publishes decoded bitmaps here.
 * Consumers: the local hazard engine (via EngineBridge), the cloud uploader
 * (BackgroundService.frameStreamLoop), and the reachability preview/status in VideoActivity.
 *
 * Holds at most one bitmap; publishing a new frame recycles the previous one. Consumers get an
 * independent ARGB_8888 copy via [acquireLatestCopy] so producer recycling can never pull a bitmap
 * out from under a consumer mid-use.
 */
object VideoFrameCache {

    data class Stats(
        val totalFrames: Long,
        val fps: Float,
        val ageMs: Long,
        val width: Int,
        val height: Int
    )

    private val lock = Any()
    private var latestBitmap: Bitmap? = null
    private var latestTimestampMs: Long = 0L

    private var totalFrames: Long = 0L
    private var fpsEma: Float = 0f
    private var lastPublishTs: Long = 0L

    fun publish(bitmap: Bitmap, timestampMs: Long = SystemClock.elapsedRealtime()) {
        synchronized(lock) {
            latestBitmap?.let {
                if (!it.isRecycled) it.recycle()
            }
            latestBitmap = bitmap
            latestTimestampMs = timestampMs

            val prev = lastPublishTs
            if (prev > 0L) {
                val dt = timestampMs - prev
                if (dt > 0L) {
                    val inst = 1000f / dt
                    fpsEma = if (fpsEma <= 0f) inst else fpsEma * 0.7f + inst * 0.3f
                }
            }
            lastPublishTs = timestampMs
            totalFrames++
        }
    }

    fun acquireLatestCopy(maxAgeMs: Long): Bitmap? {
        synchronized(lock) {
            val bitmap = latestBitmap ?: return null
            if (bitmap.isRecycled) return null
            if (latestTimestampMs <= 0L) return null
            val ageMs = SystemClock.elapsedRealtime() - latestTimestampMs
            if (ageMs > maxAgeMs) return null
            return bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }
    }

    fun latestAgeMs(): Long {
        synchronized(lock) {
            if (latestTimestampMs <= 0L) return Long.MAX_VALUE
            return SystemClock.elapsedRealtime() - latestTimestampMs
        }
    }

    fun latestSize(): Pair<Int, Int>? {
        synchronized(lock) {
            val bitmap = latestBitmap ?: return null
            if (bitmap.isRecycled) return null
            return bitmap.width to bitmap.height
        }
    }

    fun stats(): Stats {
        synchronized(lock) {
            val bmp = latestBitmap
            val age = if (latestTimestampMs <= 0L) Long.MAX_VALUE
                else SystemClock.elapsedRealtime() - latestTimestampMs
            val fresh = bmp != null && !bmp.isRecycled
            return Stats(
                totalFrames = totalFrames,
                fps = if (age > 3000L) 0f else fpsEma,
                ageMs = age,
                width = if (fresh) bmp!!.width else 0,
                height = if (fresh) bmp!!.height else 0
            )
        }
    }

    fun clear() {
        synchronized(lock) {
            latestBitmap?.let {
                if (!it.isRecycled) it.recycle()
            }
            latestBitmap = null
            latestTimestampMs = 0L
            totalFrames = 0L
            fpsEma = 0f
            lastPublishTs = 0L
        }
    }
}
