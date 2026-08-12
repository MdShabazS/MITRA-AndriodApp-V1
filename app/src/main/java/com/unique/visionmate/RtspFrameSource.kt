package com.unique.visionmate

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IVLCVout
import java.io.File
import java.io.FileOutputStream

/**
 * VLC-backed RTSP frame source.
 *
 * Hardware streams are decoded and displayed through LibVLC. PixelCopy samples that same visible
 * surface so local inference, OCR, and cloud upload read exactly the frames shown on screen.
 */
class RtspFrameSource(
    private val context: Context,
    private val rtspUrl: String,
    private val surfaceView: SurfaceView,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onStatus(status: String)
        fun onVideoSize(width: Int, height: Int) {}
    }

    private enum class TransportMode(val statusLabel: String) {
        TCP("vlc-tcp"),
        AUTO_UDP("vlc-auto/udp");

        companion object {
            fun fromStored(value: String?): TransportMode? =
                entries.firstOrNull { it.statusLabel == value }
        }
    }

    companion object {
        private const val TAG = "RtspFrameSource"
        private const val DEFAULT_WIDTH = 640
        private const val DEFAULT_HEIGHT = 480
        private const val BASE_PUBLISH_INTERVAL_MS = 450L
        private const val VLC_CACHE_MS = 60
        private const val FIRST_CAPTURE_DELAY_MS = 250L
        private const val LIVE_STALL_MS = 4_000L
        private const val LIVE_SESSION_REFRESH_MS = 5 * 60 * 1_000L
        private const val LIVE_REFRESH_MIN_FRAME_AGE_MS = 1_500L
        private const val FIRST_FRAME_STALL_TCP_MS = 3_200L
        private const val FIRST_FRAME_STALL_UDP_MS = 2_800L
        private const val FIRST_FRAME_AFTER_VOUT_GRACE_MS = 4_000L
        private const val WATCHDOG_INTERVAL_MS = 800L
        private const val RECONNECT_DELAY_MS = 450L
        private const val RECONNECT_ATTEMPT_TIMEOUT_MS = 6_000L
        private const val FRAME_DUMP_INTERVAL_MS = 2_000L
        private const val FRAME_DUMP_MAX = 40
        private const val CAPTURE_FAILURE_LOG_INTERVAL_MS = 2_000L
        private const val VISUAL_FREEZE_MS = 30_000L
        private const val VISUAL_FREEZE_MIN_SAME_FRAMES = 30
        private const val FINGERPRINT_GRID_X = 12
        private const val FINGERPRINT_GRID_Y = 8

        const val PREF_DUMP_FRAMES = "offload.debug.dumpFrames"
        const val PREF_LAST_GOOD_TRANSPORT = "rtsp.last_good_transport"
        const val BUILD_TAG = "rtsp-visual-freeze-watchdog"
    }

    private val mainHandler = Handler(context.mainLooper)
    private val readerThread = HandlerThread("rtsp-frame-reader").apply { start() }
    private val readerHandler = Handler(readerThread.looper)

    private var libVlc: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var surfaceCallback: SurfaceHolder.Callback? = null
    private var voutCallback: IVLCVout.Callback? = null
    private var captureBitmap: Bitmap? = null

    @Volatile private var captureWidth = DEFAULT_WIDTH
    @Volatile private var captureHeight = DEFAULT_HEIGHT
    @Volatile private var running = false
    @Volatile private var publishIntervalMs = BASE_PUBLISH_INTERVAL_MS
    @Volatile private var reconnectScheduled = false
    @Volatile private var reconnectRestartStarted = false
    @Volatile private var pendingReconnectReason: String? = null
    @Volatile private var capturesOk = 0L
    @Volatile private var lastFrameMs = 0L
    @Volatile private var lastVisualChangeMs = 0L
    @Volatile private var lastVideoOutputMs = 0L
    @Volatile private var playerStartedMs = 0L
    @Volatile private var captureInFlight = false
    @Volatile private var transportMode = loadLastGoodTransport()
    @Volatile private var sameVisualFrameCount = 0
    private var lastVisualSignature: Long? = null

    private val dumpFrames: Boolean by lazy {
        context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_DUMP_FRAMES, false)
    }
    private var lastDumpMs = 0L
    private var dumpCount = 0
    private var lastCaptureFailureLogMs = 0L
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    fun start() {
        if (running) return
        running = true
        capturesOk = 0L
        lastFrameMs = SystemClock.elapsedRealtime()
        lastVisualChangeMs = lastFrameMs
        lastVisualSignature = null
        sameVisualFrameCount = 0
        lastVideoOutputMs = 0L
        callbacks.onVideoSize(captureWidth, captureHeight)
        registerSurfaceCallback()
        registerThermal()
        mainHandler.post { startPlayerWhenSurfaceReady("start") }
        mainHandler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
        readerHandler.postDelayed(captureTick, FIRST_CAPTURE_DELAY_MS)
        Log.i(TAG, "frame source started url=$rtspUrl initialTransport=${transportMode.statusLabel} [build $BUILD_TAG]")
    }

    fun stop() {
        if (!running && mediaPlayer == null && libVlc == null) return
        running = false
        mainHandler.removeCallbacks(watchdog)
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.removeCallbacks(reconnectAttemptTimeoutRunnable)
        readerHandler.removeCallbacks(captureTick)
        unregisterThermal()
        unregisterSurfaceCallback()
        mainHandler.post { releasePlayer() }
        readerThread.quitSafely()
        Log.i(TAG, "frame source stopped")
    }

    // ---- LibVLC wiring ------------------------------------------------------------------------

    private fun registerSurfaceCallback() {
        if (surfaceCallback != null) return
        val callback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (!running) return
                Log.i(TAG, "SurfaceView created; LibVLC player can start")
                startPlayerWhenSurfaceReady("surface-created")
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (!running || mediaPlayer != null) return
                if (width > 0 && height > 0) startPlayerWhenSurfaceReady("surface-changed")
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (!running) return
                Log.w(TAG, "SurfaceView destroyed; releasing LibVLC player")
                callbacks.onStatus("waiting-surface")
                releasePlayer()
            }
        }
        surfaceCallback = callback
        surfaceView.holder.addCallback(callback)
        if (isSurfaceReady()) {
            startPlayerWhenSurfaceReady("surface-already-available")
        }
    }

    private fun unregisterSurfaceCallback() {
        surfaceCallback?.let { surfaceView.holder.removeCallback(it) }
        surfaceCallback = null
    }

    private fun isSurfaceReady(): Boolean {
        return surfaceView.isAttachedToWindow && surfaceView.holder.surface?.isValid == true
    }

    private fun startPlayerWhenSurfaceReady(reason: String, forceRestart: Boolean = false) {
        if (!running) return
        if (!forceRestart && mediaPlayer != null) {
            Log.d(TAG, "LibVLC player already active; ignoring duplicate start ($reason)")
            return
        }
        if (!isSurfaceReady()) {
            callbacks.onStatus("waiting-surface")
            Log.i(TAG, "waiting for SurfaceView before RTSP player ($reason)")
            lastFrameMs = SystemClock.elapsedRealtime()
            return
        }
        releasePlayer()
        playerStartedMs = SystemClock.elapsedRealtime()
        val lib = LibVLC(context, vlcOptions())
        val player = MediaPlayer(lib)
        libVlc = lib
        mediaPlayer = player

        val vout = player.vlcVout
        val callback = object : IVLCVout.Callback {
            override fun onSurfacesCreated(vlcVout: IVLCVout) {
                Log.i(TAG, "LibVLC surfaces created")
            }

            override fun onSurfacesDestroyed(vlcVout: IVLCVout) {
                Log.w(TAG, "LibVLC surfaces destroyed")
            }
        }
        voutCallback = callback
        vout.setVideoView(surfaceView)
        vout.addCallback(callback)
        vout.attachViews()

        player.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Opening -> {
                    Log.i(TAG, "LibVLC opening transport=${transportMode.statusLabel}")
                    callbacks.onStatus("opening:${transportMode.statusLabel}")
                }
                MediaPlayer.Event.Buffering -> callbacks.onStatus("buffering:${transportMode.statusLabel}")
                MediaPlayer.Event.Playing -> {
                    clearReconnectStateAfterPlayback()
                    callbacks.onStatus("live:${transportMode.statusLabel}")
                    Log.i(TAG, "LibVLC playing transport=${transportMode.statusLabel}")
                }
                MediaPlayer.Event.Paused -> callbacks.onStatus("paused:${transportMode.statusLabel}")
                MediaPlayer.Event.Stopped -> callbacks.onStatus("stopped:${transportMode.statusLabel}")
                MediaPlayer.Event.EndReached -> scheduleReconnect("ended")
                MediaPlayer.Event.EncounteredError -> {
                    Log.e(TAG, "LibVLC stream error transport=${transportMode.statusLabel}")
                    VideoFrameCache.clear()
                    callbacks.onStatus("error:${transportMode.statusLabel}")
                    clearReconnectState()
                    scheduleReconnect("error")
                }
                MediaPlayer.Event.Vout -> {
                    lastVideoOutputMs = SystemClock.elapsedRealtime()
                    if (capturesOk == 0L && reconnectScheduled && reconnectRestartStarted) {
                        clearReconnectState()
                        Log.i(TAG, "LibVLC vout arrived; keeping transport=${transportMode.statusLabel} for first frame")
                    }
                    Log.i(TAG, "LibVLC vout count=${event.voutCount}")
                    callbacks.onVideoSize(captureWidth, captureHeight)
                }
            }
        }

        val media = Media(lib, android.net.Uri.parse(rtspUrl))
        addMediaOptions(media)
        Log.i(TAG, "starting LibVLC player transport=${transportMode.statusLabel} url=$rtspUrl")
        player.media = media
        media.release()
        player.play()
        callbacks.onStatus("connecting:${transportMode.statusLabel}")
    }

    private fun vlcOptions(): ArrayList<String> {
        return arrayListOf(
            "--no-audio",
            "--network-caching=$VLC_CACHE_MS",
            "--live-caching=$VLC_CACHE_MS",
            "--rtsp-caching=$VLC_CACHE_MS",
            "--clock-jitter=0",
            "--clock-synchro=0",
            "--drop-late-frames",
            "--skip-frames"
        )
    }

    private fun addMediaOptions(media: Media) {
        media.addOption(":network-caching=$VLC_CACHE_MS")
        media.addOption(":live-caching=$VLC_CACHE_MS")
        media.addOption(":rtsp-caching=$VLC_CACHE_MS")
        media.addOption(":clock-jitter=0")
        media.addOption(":clock-synchro=0")
        media.addOption(":drop-late-frames")
        media.addOption(":skip-frames")
        if (transportMode == TransportMode.TCP) {
            media.addOption(":rtsp-tcp")
        }
    }

    private fun releasePlayer() {
        mediaPlayer?.let { player ->
            try { player.setEventListener(null) } catch (_: Exception) {}
            try {
                voutCallback?.let { player.vlcVout.removeCallback(it) }
                player.vlcVout.detachViews()
            } catch (_: Exception) {}
            try { player.stop() } catch (_: Exception) {}
            try { player.release() } catch (_: Exception) {}
        }
        mediaPlayer = null
        voutCallback = null
        libVlc?.let { try { it.release() } catch (_: Exception) {} }
        libVlc = null
    }

    // ---- SurfaceView frame capture ------------------------------------------------------------

    private val captureTick = Runnable { captureOnce() }

    private fun scheduleNextCapture() {
        if (running) readerHandler.postDelayed(captureTick, publishIntervalMs)
    }

    private fun captureOnce() {
        if (!running) return
        if (captureInFlight) { scheduleNextCapture(); return }
        if (!isSurfaceReady()) {
            scheduleNextCapture(); return
        }
        val dest = obtainCaptureBitmap(captureWidth, captureHeight)
        captureInFlight = true
        mainHandler.post {
            if (!running) {
                captureInFlight = false
                return@post
            }
            try {
                PixelCopy.request(surfaceView, dest, { result ->
                    onCaptureFinished(result, dest)
                }, readerHandler)
            } catch (e: Exception) {
                captureInFlight = false
                Log.w(TAG, "PixelCopy request failed: ${e.message}")
                scheduleNextCapture()
            }
        }
    }

    private fun onCaptureFinished(result: Int, dest: Bitmap) {
        captureInFlight = false
        try {
            if (result != PixelCopy.SUCCESS) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastCaptureFailureLogMs >= CAPTURE_FAILURE_LOG_INTERVAL_MS) {
                    lastCaptureFailureLogMs = now
                    Log.w(TAG, "PixelCopy failed result=$result")
                }
                return
            }
            val now = SystemClock.elapsedRealtime()
            lastFrameMs = now
            updateVisualFreshness(dest, now)
            if (capturesOk++ == 0L) {
                Log.i(TAG, "first frame captured ${dest.width}x${dest.height} (PixelCopy)")
                rememberLastGoodTransport()
            }
            clearReconnectStateAfterCapture()
            val out = dest.copy(Bitmap.Config.ARGB_8888, false)
            VideoFrameCache.publish(out, now)
            maybeDump(out, now)
        } catch (e: Exception) {
            Log.w(TAG, "frame publish failed: ${e.message}")
        } finally {
            scheduleNextCapture()
        }
    }

    private fun obtainCaptureBitmap(w: Int, h: Int): Bitmap {
        val existing = captureBitmap
        if (existing != null && !existing.isRecycled && existing.width == w && existing.height == h) {
            return existing
        }
        existing?.let { if (!it.isRecycled) it.recycle() }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { captureBitmap = it }
    }

    private fun updateVisualFreshness(bitmap: Bitmap, now: Long) {
        val signature = visualFingerprint(bitmap)
        val previous = lastVisualSignature
        if (previous == null || previous != signature) {
            lastVisualSignature = signature
            lastVisualChangeMs = now
            sameVisualFrameCount = 0
        } else {
            sameVisualFrameCount++
        }
    }

    private fun visualFingerprint(bitmap: Bitmap): Long {
        var hash = -0x340d631b7bdddcdbL
        val width = bitmap.width.coerceAtLeast(1)
        val height = bitmap.height.coerceAtLeast(1)
        for (gy in 0 until FINGERPRINT_GRID_Y) {
            val y = ((gy + 1) * height / (FINGERPRINT_GRID_Y + 1)).coerceIn(0, height - 1)
            for (gx in 0 until FINGERPRINT_GRID_X) {
                val x = ((gx + 1) * width / (FINGERPRINT_GRID_X + 1)).coerceIn(0, width - 1)
                val pixel = bitmap.getPixel(x, y)
                val quantized = pixel and 0x00F0F0F0
                hash = (hash xor quantized.toLong()) * 0x100000001b3L
            }
        }
        return hash
    }

    private fun maybeDump(bitmap: Bitmap, now: Long) {
        if (!dumpFrames || dumpCount >= FRAME_DUMP_MAX) return
        if (now - lastDumpMs < FRAME_DUMP_INTERVAL_MS) return
        lastDumpMs = now
        try {
            val dir = File(context.getExternalFilesDir(null), "framedump").apply { mkdirs() }
            val file = File(dir, "frame_${dumpCount.toString().padStart(3, '0')}.jpg")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            dumpCount++
            Log.i(TAG, "dumped inference frame -> ${file.absolutePath} (${bitmap.width}x${bitmap.height})")
        } catch (e: Exception) {
            Log.w(TAG, "frame dump failed: ${e.message}")
        }
    }

    // ---- Reconnect / watchdog -----------------------------------------------------------------

    private fun loadLastGoodTransport(): TransportMode {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(PREF_LAST_GOOD_TRANSPORT, null)
        val mode = TransportMode.fromStored(stored) ?: TransportMode.TCP
        if (stored != null) {
            Log.i(TAG, "using remembered RTSP transport=${mode.statusLabel}")
        }
        return mode
    }

    private fun rememberLastGoodTransport() {
        try {
            context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_LAST_GOOD_TRANSPORT, transportMode.statusLabel)
                .apply()
            Log.i(TAG, "remembered RTSP transport=${transportMode.statusLabel}")
        } catch (e: Exception) {
            Log.w(TAG, "could not remember RTSP transport: ${e.message}")
        }
    }

    private fun scheduleReconnect(reason: String) {
        if (!running || reconnectScheduled) return
        reconnectScheduled = true
        reconnectRestartStarted = false
        pendingReconnectReason = reason
        VideoFrameCache.clear()
        if (capturesOk == 0L && (reason == "stall" || reason == "error" || reason == "ended")) {
            transportMode = when (transportMode) {
                TransportMode.TCP -> TransportMode.AUTO_UDP
                TransportMode.AUTO_UDP -> TransportMode.TCP
            }
            Log.w(TAG, "no RTSP frames yet; retrying with transport=${transportMode.statusLabel}")
        }
        callbacks.onStatus("reconnecting:$reason:${transportMode.statusLabel}")
        Log.w(TAG, "scheduling reconnect ($reason)")
        mainHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
    }

    private val reconnectRunnable = Runnable {
        if (running) {
            reconnectRestartStarted = true
            lastFrameMs = SystemClock.elapsedRealtime()
            lastVisualChangeMs = lastFrameMs
            lastVisualSignature = null
            sameVisualFrameCount = 0
            startPlayerWhenSurfaceReady("reconnect", forceRestart = true)
            mainHandler.removeCallbacks(reconnectAttemptTimeoutRunnable)
            mainHandler.postDelayed(reconnectAttemptTimeoutRunnable, RECONNECT_ATTEMPT_TIMEOUT_MS)
        } else {
            clearReconnectState()
        }
    }

    private val reconnectAttemptTimeoutRunnable = Runnable {
        if (!running || !reconnectScheduled) return@Runnable
        Log.w(TAG, "reconnect attempt timed out; retrying")
        reconnectScheduled = false
        scheduleReconnect("reconnect-timeout")
    }

    private fun clearReconnectState() {
        reconnectScheduled = false
        reconnectRestartStarted = false
        pendingReconnectReason = null
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.removeCallbacks(reconnectAttemptTimeoutRunnable)
    }

    private fun clearReconnectStateAfterPlayback() {
        if (!reconnectScheduled || reconnectRestartStarted) {
            clearReconnectState()
        }
    }

    private fun clearReconnectStateAfterCapture() {
        val mustExecute = pendingReconnectReason == "live-refresh" ||
            pendingReconnectReason == "visual-freeze"
        if (!reconnectScheduled || reconnectRestartStarted || !mustExecute) {
            clearReconnectState()
        }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            if (!isSurfaceReady()) {
                callbacks.onStatus("waiting-surface")
                lastFrameMs = SystemClock.elapsedRealtime()
                mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
                return
            }
            val age = SystemClock.elapsedRealtime() - lastFrameMs
            val visualAge = if (lastVisualChangeMs > 0L) {
                SystemClock.elapsedRealtime() - lastVisualChangeMs
            } else {
                0L
            }
            val voutGraceDeadlineMs = if (lastVideoOutputMs > 0L) {
                (lastVideoOutputMs - lastFrameMs).coerceAtLeast(0L) + FIRST_FRAME_AFTER_VOUT_GRACE_MS
            } else {
                0L
            }
            val allowedStallMs = when {
                capturesOk > 0L -> LIVE_STALL_MS
                voutGraceDeadlineMs > age -> voutGraceDeadlineMs
                transportMode == TransportMode.TCP -> FIRST_FRAME_STALL_TCP_MS
                else -> FIRST_FRAME_STALL_UDP_MS
            }
            if (age > allowedStallMs && !reconnectScheduled) {
                Log.w(TAG, "watchdog: no captured frame for ${age}ms; reconnecting")
                scheduleReconnect("stall")
            } else if (
                capturesOk > 0L &&
                visualAge > VISUAL_FREEZE_MS &&
                sameVisualFrameCount >= VISUAL_FREEZE_MIN_SAME_FRAMES &&
                !reconnectScheduled
            ) {
                Log.w(
                    TAG,
                    "watchdog: visual freeze for ${visualAge}ms across $sameVisualFrameCount copied frames; reconnecting"
                )
                scheduleReconnect("visual-freeze")
            } else if (
                capturesOk > 0L &&
                playerStartedMs > 0L &&
                SystemClock.elapsedRealtime() - playerStartedMs > LIVE_SESSION_REFRESH_MS &&
                age > LIVE_REFRESH_MIN_FRAME_AGE_MS &&
                !reconnectScheduled
            ) {
                Log.w(TAG, "watchdog: refreshing stale live RTSP session age=${age}ms")
                scheduleReconnect("live-refresh")
            }
            mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    // ---- Thermal ------------------------------------------------------------------------------

    private fun registerThermal() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val listener = PowerManager.OnThermalStatusChangedListener { status ->
            publishIntervalMs = when {
                status >= PowerManager.THERMAL_STATUS_EMERGENCY -> BASE_PUBLISH_INTERVAL_MS * 4
                status >= PowerManager.THERMAL_STATUS_CRITICAL -> BASE_PUBLISH_INTERVAL_MS * 2
                else -> BASE_PUBLISH_INTERVAL_MS
            }
            Log.i(TAG, "thermal status=$status -> publishInterval=${publishIntervalMs}ms")
        }
        thermalListener = listener
        try {
            pm.addThermalStatusListener(listener)
            Log.i(TAG, "thermal listener registered (current status=${pm.currentThermalStatus})")
        } catch (e: Exception) {
            Log.w(TAG, "thermal listener registration failed: ${e.message}")
        }
    }

    private fun unregisterThermal() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        thermalListener?.let { try { pm.removeThermalStatusListener(it) } catch (_: Exception) {} }
        thermalListener = null
    }
}
