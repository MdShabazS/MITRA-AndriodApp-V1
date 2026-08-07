package com.unique.visionmate

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.unique.visionmate.engine.HazardFrameResult
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import java.util.Locale
import org.msgpack.core.MessagePack
import java.io.ByteArrayOutputStream
import android.graphics.Bitmap
import android.content.Context
import com.unique.visionmate.engine.HazardObserver
import com.unique.visionmate.engine.SkipReason
import com.unique.visionmate.engine.TelemetryEvent
import java.util.concurrent.atomic.AtomicInteger

/**
 * Live back-camera path for the offload engine.
 *
 * Opens the back camera via CameraX, feeds preview frames into the same
 * HazardEngine that the offline test activity uses. The engine starts
 * automatically as soon as the camera is ready (no flag toggle needed).
 *
 * This debug/test path does not start the voice service automatically. Keeping
 * SpeechRecognizer active here causes repeated mic start/stop sounds on some
 * OPPO/ColorOS devices while the camera engine is running.
 */
class CameraActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CameraTest"
    }

    private lateinit var previewView: PreviewView
    private lateinit var statusView: TextView

    // Spoken feedback — the user is visually impaired, so errors must be heard, not just shown.
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false

    private val processed = AtomicInteger(0)
    private val dropped = AtomicInteger(0)
    @Volatile private var latestSummary: String = "(no frames yet)"

    // Optional WebSocket to stream frame metadata/images
    private var wsClient: OkHttpClient? = null
    private var webSocket: WebSocket? = null
    private var wsAttempts = 0
    private var deviceId: String? = null
    private val seqCounter = AtomicInteger(0)
    private val wsExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val framePublishHandler = Handler(Looper.getMainLooper())
    private var framePublisherRunning = false
    private val framePublisher = object : Runnable {
        override fun run() {
            if (!framePublisherRunning) return
            try {
                previewView.bitmap?.let { bitmap ->
                    VideoFrameCache.publish(bitmap, SystemClock.elapsedRealtime())
                }
            } catch (e: Exception) {
                Log.w(TAG, "preview frame publish failed: ${e.message}")
            } finally {
                if (framePublisherRunning) {
                    framePublishHandler.postDelayed(this, 500L)
                }
            }
        }
    }
    // For testing: use local server via adb reverse. For production: change to actual endpoint.
    private val WS_URL = "ws://127.0.0.1:8766/ws"

    private val requestPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val cam = results[Manifest.permission.CAMERA] == true
        if (cam) {
            startCameraAndEngine()
        } else {
            statusView.text = "Camera permission denied — engine cannot start"
            speak("Camera not available. Please allow camera access to use MITRA.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stopVoiceRuntimeForCameraQa()
        setContentView(R.layout.activity_camera)
        previewView = findViewById(R.id.previewView)
        statusView = findViewById(R.id.statusView)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                ttsReady = true
            }
        }

        val needCam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        if (!needCam) startCameraAndEngine()

        val toRequest = mutableListOf<String>()
        if (needCam) toRequest += Manifest.permission.CAMERA
        if (toRequest.isNotEmpty()) requestPerms.launch(toRequest.toTypedArray())
    }

    private fun stopVoiceRuntimeForCameraQa() {
        MitraRuntime.setActive(this, false)
        try {
            startService(Intent(this, BackgroundService::class.java).apply {
                action = BackgroundService.ACTION_STOP_MITRA_RUNTIME
            })
        } catch (e: Exception) {
            Log.w(TAG, "voice runtime stop skipped for camera QA: ${e.message}")
        }
    }

    private fun startCameraAndEngine() {
        statusView.text = "Starting camera..."
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = try {
                providerFuture.get()
            } catch (e: Exception) {
                Log.e(TAG, "ProcessCameraProvider get failed: ${e.message}", e)
                statusView.text = "Camera provider error: ${e.message}"
                speak("Camera not found. The camera could not start.")
                return@addListener
            }
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                Log.i(TAG, "back camera bound to lifecycle")
            } catch (e: Exception) {
                Log.e(TAG, "camera bind failed: ${e.message}", e)
                statusView.text = "Camera bind failed: ${e.message}"
                speak("Camera not found. The camera could not start.")
                return@addListener
            }

            // PreviewView.bitmap returns the latest displayed frame as ARGB_8888 — same shape
            // as VideoActivity's TextureView.getBitmap(). The engine pipeline is identical.
            val observer = object : HazardObserver {
                override fun onResult(result: HazardFrameResult) {
                    MobileInferenceState.update(result)
                    processed.incrementAndGet()
                    val det = result.detectionsByFeature.mapValues { it.value.size }
                    val topLine = result.detectionsByFeature.entries
                        .filter { it.value.isNotEmpty() }
                        .joinToString(" | ") { (f, list) ->
                            val top = list.maxByOrNull { it.score } ?: list.first()
                            val box = top.boxLtrb?.let {
                                "[${"%.2f".format(it[0])},${"%.2f".format(it[1])},${"%.2f".format(it[2])},${"%.2f".format(it[3])}]"
                            } ?: ""
                            "$f=${top.label}@${"%.2f".format(top.score)}$box(n=${list.size})"
                        }
                    latestSummary = "f=${result.frameId} dn=${result.dayNight} scene=${result.sceneType}\n" +
                        (if (topLine.isNotEmpty()) "$topLine\n" else "(no detections)\n") +
                        "counts=$det  latencies=${result.latenciesMs}"
                    Log.i(TAG, "result: f=${result.frameId} dn=${result.dayNight} scene=${result.sceneType} skipReason=${result.skipReason} skippedCount=${result.skippedFeatures.size} ${if (topLine.isEmpty()) "(no detections)" else topLine}")
                    runOnUiThread { updateStatus() }

                    // Send frame metadata (and optional image) over WebSocket
                    try {
                        sendFrameOverWebSocket(result)
                    } catch (e: Exception) {
                        Log.w(TAG, "WS send failed: ${e.message}")
                    }
                }

                override fun onTelemetry(event: TelemetryEvent) {
                    when (event) {
                        is TelemetryEvent.QueueDropStale -> dropped.incrementAndGet()
                        is TelemetryEvent.FeatureDisabled -> Log.w(TAG, "disabled: ${event.feature} reason=${event.reason}")
                        is TelemetryEvent.FeatureTimeout -> Log.w(TAG, "timeout: ${event.feature} on f=${event.frameId}")
                        is TelemetryEvent.FeatureException -> Log.e(TAG, "exception: ${event.feature} -> ${event.message}")
                        is TelemetryEvent.FrameSkippedNight -> { /* counted via onResult.skipReason */ }
                    }
                    runOnUiThread { updateStatus() }
                }
            }
            EngineBridge.attachAlways(this, { previewView.bitmap }, observer)
            startFramePublisher()
            statusView.text = "Engine starting on back camera..."
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startFramePublisher() {
        if (framePublisherRunning) return
        VideoFrameCache.clear()
        framePublisherRunning = true
        framePublishHandler.post(framePublisher)
        Log.i(TAG, "phone camera frame publisher started")
    }

    private fun connectWebSocketIfNeeded() {
        if (webSocket != null) return
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val deviceIdPref = prefs.getString(MainActivity.KEY_UNIQUE_CODE, null)
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val resolved = deviceIdPref ?: androidId ?: "unknown"
        deviceId = resolved
        try {
            wsClient = OkHttpClient.Builder().build()
            val request = Request.Builder().url("$WS_URL/$deviceId").build()
            webSocket = wsClient!!.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "Camera WS connected")
                    wsAttempts = 0
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "Camera WS failure: ${t.message}")
                    this@CameraActivity.webSocket = null
                    // Exponential backoff reconnect
                    wsAttempts++
                    val delayMs = (1000L * Math.min(60, (1 shl wsAttempts))).coerceAtMost(60000L)
                    Handler(Looper.getMainLooper()).postDelayed({ connectWebSocketIfNeeded() }, delayMs)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "Camera WS closed: $code $reason")
                    this@CameraActivity.webSocket = null
                }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    try {
                        val unpacker = MessagePack.newDefaultUnpacker(bytes.toByteArray())
                        // Simple log of unpacked value
                        if (unpacker.hasNext()) {
                            val v = unpacker.unpackValue()
                            Log.i(TAG, "WS msg unpacked: $v")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to unpack WS message: ${e.message}")
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.i(TAG, "WS text: $text")
                    // Simple ACK handling: "ack:frame_<id>" or "ack:<count>"
                    if (text.startsWith("ack:frame_")) {
                        Log.d(TAG, "Received ACK for ${text.removePrefix("ack:")}")
                    } else if (text.startsWith("ack:")) {
                        Log.d(TAG, "Received ACK count=${text.removePrefix("ack:")}")
                    }
                    runOnUiThread { statusView.text = "Server: $text" }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "connectWebSocketIfNeeded failed: ${e.message}")
            webSocket = null
        }
    }

    private fun sendFrameOverWebSocket(result: HazardFrameResult) {
        val ws = webSocket ?: return
        // Perform MessagePack packing off the caller thread to avoid blocking engine/UI
        val frameId = result.frameId
        val seq = seqCounter.incrementAndGet()
        val dev = deviceId ?: "unknown"
        wsExecutor.submit {
            var packer = MessagePack.newDefaultBufferPacker()
            try {
                // top-level: type + meta + frame
                packer.packMapHeader(3)
                packer.packString("type"); packer.packString("frame_result")

                // metadata: deviceId, seq, schema
                packer.packString("meta")
                packer.packMapHeader(3)
                packer.packString("deviceId"); packer.packString(dev)
                packer.packString("seq"); packer.packInt(seq)
                packer.packString("schema_version"); packer.packString("1.0")

                // frame
                packer.packString("frame")
                packer.packMapHeader(7)
                packer.packString("frameId"); packer.packLong(frameId)
                packer.packString("tsMs"); packer.packLong(result.tsMs)
                packer.packString("dayNight"); packer.packString(result.dayNight?.name ?: "UNKNOWN")
                packer.packString("scene"); packer.packString(result.sceneType?.name ?: "UNKNOWN")

                // executed
                packer.packString("executed"); packer.packArrayHeader(result.executedFeatures.size)
                for (f in result.executedFeatures) packer.packString(f.name)

                // skipped
                packer.packString("skipped"); packer.packMapHeader(result.skippedFeatures.size)
                for ((k, v) in result.skippedFeatures) {
                    packer.packString(k.name); packer.packString(v.name)
                }

                // detections
                packer.packString("detections")
                packer.packMapHeader(result.detectionsByFeature.size)
                for ((k, list) in result.detectionsByFeature) {
                    packer.packString(k.name)
                    packer.packArrayHeader(list.size)
                    for (d in list) {
                        val box = d.boxLtrb
                        packer.packMapHeader(if (box != null) 3 else 2)
                        packer.packString("label"); packer.packString(d.label)
                        packer.packString("score"); packer.packFloat(d.score)
                        if (box != null) {
                            packer.packString("box")
                            packer.packArrayHeader(box.size)
                            for (v in box) packer.packFloat(v)
                        }
                    }
                }

                // latencies
                packer.packString("latencies"); packer.packMapHeader(result.latenciesMs.size)
                for ((k, v) in result.latenciesMs) {
                    packer.packString(k.name); packer.packLong(v)
                }

                packer.close()
                val sent = ws.send(packer.toByteArray().toByteString())
                Log.d(TAG, "frame_result sent frame=$frameId seq=$seq success=$sent")
            } catch (e: Exception) {
                Log.w(TAG, "WS pack/send failed for f=$frameId: ${e.message}")
            } finally {
                try { packer.close() } catch (ignored: Exception) {}
            }
        }
    }

    private fun updateStatus() {
        statusView.text = buildString {
            append("Submitted=${EngineBridge.submitted.get()}  Processed=${processed.get()}  Dropped=${dropped.get()}\n")
            append(latestSummary)
        }
    }

    private fun speak(text: String) {
        val engine = tts ?: return
        if (!ttsReady) return
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "camera_status")
    }

    override fun onDestroy() {
        super.onDestroy()
        framePublisherRunning = false
        framePublishHandler.removeCallbacks(framePublisher)
        EngineBridge.detach()
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        tts = null
        ttsReady = false
        try {
            webSocket?.close(1000, "user_exit")
            webSocket = null
        } catch (e: Exception) { /* ignore */ }
        try {
            wsClient?.dispatcher?.executorService?.shutdown()
            wsExecutor.shutdown()
        } catch (e: Exception) { /* ignore */ }
    }
}
