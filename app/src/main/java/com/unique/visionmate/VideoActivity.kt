package com.unique.visionmate

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.unique.visionmate.engine.HazardFrameResult
import org.json.JSONObject

/**
 * Reachability + status surface for the MITRA RTSP stream — NOT a media player.
 *
 * Per the architecture rework, the on-screen video exists only to confirm the stream is reaching the
 * app. The single canonical decode lives in [RtspFrameSource] (LibVLC -> SurfaceView). This activity:
 *   - performs the Wi-Fi bind to the MITRA network, then starts [RtspFrameSource],
 *   - renders the latest canonical frame into a small preview ImageView (~2 fps) + a status card,
 *   - attaches the local hazard engine once real frames are flowing,
 *   - drives the cloud streamer in BackgroundService.
 *
 * Both the preview and inference read the exact same [VideoFrameCache] frames, so what you see is what
 * the models see.
 */
class VideoActivity : AppCompatActivity(), RtspFrameSource.Callbacks {

    companion object {
        private const val TAG = "VideoActivity"
        private const val RTSP_URL = "rtsp://10.42.0.1:8554/stream"
        private const val STREAM_STALE_MS = 25000L
        private const val UI_TICK_MS = 500L
        private const val CAPTURE_MAX_WIDTH = 640
        private const val CAPTURE_MAX_HEIGHT = 360
        private const val WIFI_BIND_RETRY_MS = 1_500L
        private const val WIFI_BIND_MAX_RETRIES = 40
        private const val MAX_RTSP_DISPLAY_UPSCALE = 1.5f

        /** Latest canonical frame for voice-OCR / cloud upload. Reads the frame bus, never the UI. */
        fun getScreenBitmap(): Bitmap? = VideoFrameCache.acquireLatestCopy(STREAM_STALE_MS)

        fun isStreamFrameFresh(): Boolean = VideoFrameCache.latestAgeMs() <= STREAM_STALE_MS

        fun requestScreenBitmap(callback: (Bitmap?) -> Unit) {
            requestScreenBitmap(CAPTURE_MAX_WIDTH, CAPTURE_MAX_HEIGHT, callback)
        }

        fun requestScreenBitmap(width: Int, height: Int, callback: (Bitmap?) -> Unit) {
            val bitmap = VideoFrameCache.acquireLatestCopy(STREAM_STALE_MS)?.let { source ->
                if (source.width == width && source.height == height) {
                    source
                } else {
                    val scaled = Bitmap.createScaledBitmap(source, width, height, true)
                    if (!source.isRecycled) source.recycle()
                    scaled
                }
            }
            callback(bitmap)
        }
    }

    private lateinit var videoSurfaceView: SurfaceView
    private lateinit var streamStatusView: TextView
    private lateinit var cloudStatusView: TextView
    private lateinit var modelStatusView: TextView
    private lateinit var localInferenceView: TextView
    private lateinit var detectionDebugView: TextView
    private lateinit var stopMitraButton: Button
    private lateinit var framePreviewView: ImageView
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var wifiManager: WifiManager

    private val mainHandler = Handler(Looper.getMainLooper())
    private var frameSource: RtspFrameSource? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var engineAttached = false
    private var sourceStarted = false
    private var stopMitraRequested = false
    private var previewBitmap: Bitmap? = null
    private var wifiBindAttempts = 0
    @Volatile private var streamStatus: String = "connecting"
    @Volatile private var latestLocalResult: HazardFrameResult? = null
    private val backgroundUiReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BackgroundService.ACTION_BACKGROUND_MITRA_UI) {
                moveTaskToBack(true)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        videoSurfaceView = findViewById(R.id.videoSurfaceView)
        streamStatusView = findViewById(R.id.streamStatusView)
        cloudStatusView = findViewById(R.id.cloudStatusView)
        modelStatusView = findViewById(R.id.modelStatusView)
        localInferenceView = findViewById(R.id.localInferenceView)
        detectionDebugView = findViewById(R.id.detectionDebugView)
        stopMitraButton = findViewById(R.id.stopMitraButton)
        framePreviewView = findViewById(R.id.framePreviewView)

        modelStatusView.text = buildModelStatus()
        localInferenceView.text = "Local 1 FPS\nWaiting for first inference result"
        detectionDebugView.text = "Detections\nWaiting for first inference result"
        modelStatusView.visibility = View.GONE
        localInferenceView.visibility = View.GONE
        detectionDebugView.visibility = View.GONE
        framePreviewView.visibility = View.GONE
        stopMitraButton.setOnClickListener { stopMitraAndExit() }

        ContextCompat.registerReceiver(
            this,
            backgroundUiReceiver,
            IntentFilter(BackgroundService.ACTION_BACKGROUND_MITRA_UI),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        onBackPressedDispatcher.addCallback(this) {
            moveTaskToBack(true)
        }

        startService(Intent(this, BackgroundService::class.java).apply {
            action = BackgroundService.ACTION_START_MITRA_RUNTIME
        })
        startService(Intent(this, BackgroundService::class.java).apply {
            action = BackgroundService.ACTION_START_STREAMING
        })
        ensureMitraNetworkThenStart()
        mainHandler.postDelayed(uiTick, UI_TICK_MS)

        EngineBridge.setResultListener { result ->
            latestLocalResult = result
            Log.d(TAG, "local inference: ${buildLocalInferenceSummary(result).replace('\n', ' ')}")
        }
    }

    // ---- RtspFrameSource.Callbacks ------------------------------------------------------------

    override fun onStatus(status: String) {
        streamStatus = status
        runOnUiThread { renderStatusCard() }
    }

    override fun onVideoSize(width: Int, height: Int) {
        runOnUiThread { fitSurfaceToVideoAspect(width, height) }
    }

    private fun fitSurfaceToVideoAspect(videoWidth: Int, videoHeight: Int) {
        if (videoWidth <= 0 || videoHeight <= 0) return
        val parent = videoSurfaceView.parent as? View ?: return
        val parentWidth = parent.width
        val parentHeight = parent.height
        if (parentWidth <= 0 || parentHeight <= 0) {
            videoSurfaceView.post { fitSurfaceToVideoAspect(videoWidth, videoHeight) }
            return
        }

        val fitScale = minOf(
            parentWidth.toFloat() / videoWidth.toFloat(),
            parentHeight.toFloat() / videoHeight.toFloat()
        )
        val displayScale = minOf(fitScale, MAX_RTSP_DISPLAY_UPSCALE)
        val targetWidth = (videoWidth * displayScale).toInt().coerceAtLeast(1)
        val targetHeight = (videoHeight * displayScale).toInt().coerceAtLeast(1)

        val params = (videoSurfaceView.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(targetWidth, targetHeight)
        if (params.width == targetWidth && params.height == targetHeight && params.gravity == Gravity.CENTER) return
        params.width = targetWidth
        params.height = targetHeight
        params.gravity = Gravity.CENTER
        videoSurfaceView.layoutParams = params
        Log.i(TAG, "RTSP surface fit ${videoWidth}x${videoHeight} into ${targetWidth}x${targetHeight}")
    }

    // ---- Wi-Fi bind + source start ------------------------------------------------------------

    private fun ensureMitraNetworkThenStart() {
        if (sourceStarted || isFinishing || isDestroyed) return

        BackgroundService.currentHeldWifiNetwork()?.let { heldNetwork ->
            bindRouteAndStart(heldNetwork, "held MITRA network from BackgroundService")
            return
        }

        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val ssid = prefs.getString(MainActivity.KEY_SSID, null)
        val password = prefs.getString(MainActivity.KEY_PASSWORD, null)

        currentConnectedSsid()?.takeIf { isMitraSsid(it) }?.let { connectedSsid ->
            val routeWifi = routableWifiNetwork()
            if (routeWifi != null) {
                bindRouteAndStart(routeWifi, "already-connected MITRA WiFi route for $connectedSsid")
                return
            }
            Log.w(TAG, "MITRA SSID is connected but no routable WiFi Network object was available")
            if (!ssid.isNullOrBlank() && !password.isNullOrBlank()) {
                requestMitraNetwork(ssid, password)
                return
            }
            streamStatus = "wifi-route-missing"
            renderStatusCard()
            return
        }

        if (ssid.isNullOrBlank() || password.isNullOrBlank()) {
            Log.w(TAG, "No saved MITRA WiFi credentials; waiting for manual MITRA WiFi route")
            scheduleWifiBindRetry("missing-credentials")
            return
        }

        requestMitraNetwork(ssid, password)
    }

    private fun requestMitraNetwork(ssid: String, password: String) {
        streamStatus = "binding-wifi"
        networkCallback?.let { try { connectivityManager.unregisterNetworkCallback(it) } catch (_: Exception) {} }

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(password)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread { bindRouteAndStart(network, "requestNetwork MITRA network $ssid") }
            }

            override fun onUnavailable() {
                Log.e(TAG, "MITRA network unavailable for VideoActivity")
                runOnUiThread { scheduleWifiBindRetry("request-unavailable") }
            }
        }
        networkCallback = callback
        try {
            connectivityManager.requestNetwork(request, callback, 10000)
        } catch (e: Exception) {
            Log.e(TAG, "Failed requesting MITRA network: ${e.message}", e)
            scheduleWifiBindRetry("request-error")
        }
    }

    private fun bindRouteAndStart(network: Network, label: String) {
        if (sourceStarted || isFinishing || isDestroyed) return
        try {
            connectivityManager.bindProcessToNetwork(network)
            Log.d(TAG, "Bound VideoActivity to $label")
            streamStatus = "wifi-bound"
            wifiBindAttempts = 0
            startFrameSourceOnce()
        } catch (e: Exception) {
            Log.e(TAG, "Failed binding $label: ${e.message}", e)
            scheduleWifiBindRetry("bind-error")
        }
    }

    private fun scheduleWifiBindRetry(reason: String) {
        if (sourceStarted || isFinishing || isDestroyed) return
        if (wifiBindAttempts >= WIFI_BIND_MAX_RETRIES) {
            streamStatus = "wifi-bind-failed"
            renderStatusCard()
            Log.e(TAG, "MITRA WiFi bind failed after $wifiBindAttempts attempts ($reason)")
            return
        }
        wifiBindAttempts++
        streamStatus = "waiting-wifi"
        renderStatusCard()
        Log.d(TAG, "Waiting for MITRA WiFi route ($reason), attempt=$wifiBindAttempts")
        mainHandler.postDelayed({ ensureMitraNetworkThenStart() }, WIFI_BIND_RETRY_MS)
    }

    @Suppress("DEPRECATION")
    private fun currentConnectedSsid(): String? {
        return try {
            wifiManager.connectionInfo?.ssid?.trim()?.trim('"')?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun isMitraSsid(ssid: String?): Boolean {
        val normalized = ssid?.trim()?.trim('"') ?: return false
        return normalized.equals(MainActivity.TARGET_SSID, ignoreCase = true) ||
            normalized.contains("MITRA", ignoreCase = true)
    }

    private fun routableWifiNetwork(): Network? {
        val active = connectivityManager.activeNetwork
        val activeCaps = active?.let { connectivityManager.getNetworkCapabilities(it) }
        if (active != null && activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) return active
        @Suppress("DEPRECATION")
        return connectivityManager.allNetworks.firstOrNull { network ->
            connectivityManager.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    private fun startFrameSourceOnce() {
        if (sourceStarted) return
        sourceStarted = true
        VideoFrameCache.clear()
        frameSource = RtspFrameSource(applicationContext, RTSP_URL, videoSurfaceView, this).also { it.start() }
        Log.i(TAG, "RtspFrameSource started")
    }

    // ---- Periodic UI: preview + status + engine attach ----------------------------------------

    private val uiTick = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            renderStatusCard()
            renderCloudStatusCard()
            if (!engineAttached && isStreamFrameFresh()) attachEngine()
            mainHandler.postDelayed(this, UI_TICK_MS)
        }
    }

    private fun renderFramePreview() {
        framePreviewView.visibility = View.GONE
    }

    private fun renderStatusCard() {
        val s = VideoFrameCache.stats()
        val hasFreshFrame = s.ageMs != Long.MAX_VALUE && s.ageMs <= 1500L
        streamStatusView.text = buildString {
            append("Stream\n")
            append("State: ")
            append(if (hasFreshFrame) "live" else streamStatus)
            append('\n')
            append("Sampled: ${s.totalFrames}\n")
            append("FPS: ${"%.1f".format(s.fps)}")
            if (s.width > 0) append("  ${s.width}x${s.height}")
            append('\n')
            append("Last frame: ")
            append(formatAge(s.ageMs))
            append('\n')
            append("Source: RTSP H.264")
        }
    }

    private fun renderCloudStatusCard() {
        val telemetry = CloudFrameResultStore.telemetry()
        cloudStatusView.text = buildString {
            append("Cloud\n")
            append("State: ").append(telemetry.status).append('\n')
            append("Frames sent: ").append(telemetry.framesSent).append('\n')
            append("Replies: ").append(telemetry.repliesReceived).append('\n')
            append("ACK: ")
            append(telemetry.lastAckSeq?.let { "#$it" } ?: "none")
            if (telemetry.ackTimeouts > 0L) append("  timeout=").append(telemetry.ackTimeouts)
            append('\n')
            append("Last reply: ").append(formatAgeFromTimestamp(telemetry.lastReplyAtMs)).append('\n')
            append("Type: ").append(telemetry.lastReplyType ?: "n/a")
            telemetry.lastReplyFrameId?.let { append('\n').append("Frame: ").append(it.take(18)) }
            telemetry.lastOutputText?.takeIf { it.isNotBlank() }?.let {
                append('\n').append("Text: ").append(it.take(44))
            }
        }
    }

    private fun formatAge(ageMs: Long): String {
        return when {
            ageMs == Long.MAX_VALUE -> "never"
            ageMs < 1_000L -> "${ageMs}ms ago"
            ageMs < 60_000L -> "${ageMs / 1_000L}s ago"
            else -> "${ageMs / 60_000L}m ago"
        }
    }

    private fun formatAgeFromTimestamp(timestampMs: Long): String {
        if (timestampMs <= 0L) return "none"
        return formatAge(SystemClock.elapsedRealtime() - timestampMs)
    }

    private fun attachEngine() {
        engineAttached = true
        EngineBridge.attachAlways(this, { VideoFrameCache.acquireLatestCopy(STREAM_STALE_MS) }, object : com.unique.visionmate.engine.HazardObserver {
            override fun onResult(result: com.unique.visionmate.engine.HazardFrameResult) {
                MobileInferenceState.update(result)
                latestLocalResult = result
                Log.d(TAG, "engine result: ${buildLocalInferenceSummary(result).replace('\n', ' ')}")
            }

            override fun onTelemetry(event: com.unique.visionmate.engine.TelemetryEvent) {
                Log.w(TAG, "telemetry: $event")
            }
        })
        Log.d(TAG, "Engine attached to canonical frame source")
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(uiTick)
        EngineBridge.setResultListener(null)
        EngineBridge.detach()
        engineAttached = false
        frameSource?.stop()
        frameSource = null
        sourceStarted = false
        VideoFrameCache.clear()
        previewBitmap?.let { if (!it.isRecycled) it.recycle() }
        previewBitmap = null
        networkCallback?.let { try { connectivityManager.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        networkCallback = null
        try { unregisterReceiver(backgroundUiReceiver) } catch (_: Exception) {}
        if (stopMitraRequested) {
            startService(Intent(this, BackgroundService::class.java).apply {
                action = BackgroundService.ACTION_STOP_STREAMING
            })
        }
    }

    private fun stopMitraAndExit() {
        stopMitraRequested = true
        MitraRuntime.setActive(this, false)
        stopLocalRuntime()
        startService(Intent(this, BackgroundService::class.java).apply {
            action = BackgroundService.ACTION_STOP_MITRA_RUNTIME
        })
        startService(Intent(this, BackgroundService::class.java).apply {
            action = BackgroundService.ACTION_STOP_STREAMING
        })
        moveTaskToBack(true)
        finish()
    }

    private fun stopLocalRuntime() {
        mainHandler.removeCallbacks(uiTick)
        EngineBridge.setResultListener(null)
        EngineBridge.detach()
        engineAttached = false
        frameSource?.stop()
        frameSource = null
        sourceStarted = false
        VideoFrameCache.clear()
        try { connectivityManager.bindProcessToNetwork(null) } catch (_: Exception) {}
        networkCallback?.let { try { connectivityManager.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        networkCallback = null
    }

    // ---- Overlay text builders ----------------------------------------------------------------

    private fun buildLocalInferenceSummary(result: HazardFrameResult): String {
        val executed = result.executedFeatures.joinToString()
        val skipped = result.skippedFeatures.keys.joinToString()
        return buildString {
            append("Local 1 FPS\n")
            append("Day/Night: ${result.dayNight}  Scene: ${result.sceneType}\n")
            append("Executed: ${if (executed.isBlank()) "none" else executed}\n")
            append("Skipped: ${if (skipped.isBlank()) "none" else skipped}")
        }
    }

    private fun buildDetectionSummary(result: HazardFrameResult): String {
        val ordered = listOf(
            com.unique.visionmate.engine.Feature.OCR,
            com.unique.visionmate.engine.Feature.FIRE_SMOKE,
            com.unique.visionmate.engine.Feature.WET_DRY,
            com.unique.visionmate.engine.Feature.PEDESTRIAN,
            com.unique.visionmate.engine.Feature.POTHOLE,
            com.unique.visionmate.engine.Feature.ELECTRIC_POLE
        )
        val lines = ordered.map { feature ->
            val detections = result.detectionsByFeature[feature].orEmpty()
            if (detections.isEmpty()) {
                val skip = result.skippedFeatures[feature]
                if (skip != null) "${feature.name}: skipped ($skip)" else "${feature.name}: no detections"
            } else {
                val preview = detections.take(3).joinToString(" | ") {
                    "${it.label}@${"%.2f".format(it.score)}"
                }
                "${feature.name}: ${detections.size} -> $preview"
            }
        }
        return "Detections\n" + lines.joinToString("\n")
    }

    private fun buildDebugOverlay(
        localResult: HazardFrameResult?,
        cloudResult: CloudFrameResult?,
        navigationDecision: NavigationDecision?
    ): String {
        val localBlock = localResult?.let { buildDetectionSummary(it) } ?: "Detections\nWaiting for first inference result"
        val cloudBlock = buildString {
            append("\n\nCloud\n")
            if (cloudResult == null) {
                append("Waiting for first cloud response")
            } else {
                append("type=").append(cloudResult.responseType ?: "n/a").append('\n')
                append("frame=").append(cloudResult.frameId ?: "n/a").append('\n')
                append("cloud_text=").append((cloudResult.outputText ?: "n/a").take(120))
            }
        }
        val navBlock = buildString {
            append("\n\nNavigation\n")
            if (navigationDecision == null) {
                append("Waiting for local navigation decision")
            } else {
                append("action=").append(navigationDecision.finalAction).append("  ")
                append("dir=").append(navigationDecision.direction).append('\n')
                append("text=").append(navigationDecision.finalText).append('\n')
                append("reason=").append(navigationDecision.reason).append('\n')
                append("debug=").append(navigationDecision.debugSummary)
            }
        }
        return localBlock + cloudBlock + navBlock
    }

    private fun buildModelStatus(): String {
        return try {
            val manifest = assets.open("models/manifest.json").bufferedReader().use { it.readText() }
            val features = JSONObject(manifest).getJSONObject("features")
            val enabled = features.keys().asSequence().filter { key ->
                features.getJSONObject(key).optBoolean("enabled", false)
            }.toList()
            val modelNames = if (enabled.isEmpty()) "none" else enabled.joinToString()
            "Models: $modelNames\nInference: local 1 FPS active"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read model manifest: ${e.message}", e)
            "Models: unavailable\nInference: local 1 FPS active"
        }
    }
}
