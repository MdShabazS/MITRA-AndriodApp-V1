package com.unique.visionmate

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.unique.visionmate.engine.DayNight
import com.unique.visionmate.engine.Detection
import com.unique.visionmate.engine.Feature
import com.unique.visionmate.engine.HazardFrameResult
import com.unique.visionmate.engine.SceneType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.msgpack.core.MessagePack
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class BackgroundService : Service() {

    companion object {
        const val CHANNEL_ID = "visionmate_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_HOLD_WIFI = "com.unique.visionmate.ACTION_HOLD_WIFI"
        const val ACTION_PAUSE_LISTENING = "com.unique.visionmate.ACTION_PAUSE_LISTENING"
        const val ACTION_RESUME_LISTENING = "com.unique.visionmate.ACTION_RESUME_LISTENING"
        const val ACTION_TAKE_PICTURE = "com.unique.visionmate.ACTION_TAKE_PICTURE"
        const val ACTION_UPDATE_WAKE_WORD = "com.unique.visionmate.ACTION_UPDATE_WAKE_WORD"
        const val ACTION_START_STREAMING = "com.unique.visionmate.ACTION_START_STREAMING"
        const val ACTION_STOP_STREAMING = "com.unique.visionmate.ACTION_STOP_STREAMING"
        const val ACTION_START_MITRA_RUNTIME = "com.unique.visionmate.ACTION_START_MITRA_RUNTIME"
        const val ACTION_STOP_MITRA_RUNTIME = "com.unique.visionmate.ACTION_STOP_MITRA_RUNTIME"
        const val ACTION_RESTART_RUNTIME = "com.unique.visionmate.ACTION_RESTART_RUNTIME"
        const val ACTION_BACKGROUND_MITRA_UI = "com.unique.visionmate.ACTION_BACKGROUND_MITRA_UI"
        const val ACTION_STREAMING_STATUS = "com.unique.visionmate.ACTION_STREAMING_STATUS"
        const val ACTION_ACTIVATE_VOICE = "com.unique.visionmate.ACTION_ACTIVATE_VOICE"
        const val ACTION_VOICE_START_SEARCH = "com.unique.visionmate.ACTION_VOICE_START_SEARCH"
        const val ACTION_SPEAK_TEXT = "com.unique.visionmate.ACTION_SPEAK_TEXT"
        const val EXTRA_SPEAK_TEXT = "com.unique.visionmate.EXTRA_SPEAK_TEXT"
        const val EXTRA_STATUS_MESSAGE = "com.unique.visionmate.EXTRA_STATUS_MESSAGE"
        private const val STREAM_CAPTURE_WIDTH = 640
        private const val STREAM_CAPTURE_HEIGHT = 360
        private const val CLOUD_FRAME_MAX_AGE_MS = 3_000L
        private const val RESTART_REQUEST_CODE = 3021
        private const val STT_LANGUAGE_PACK_ERROR_CODE =
            MitraSpeechRecognizerConfig.ERROR_LANGUAGE_NOT_SUPPORTED
        private const val STT_LANGUAGE_UNAVAILABLE_ERROR_CODE =
            MitraSpeechRecognizerConfig.ERROR_LANGUAGE_UNAVAILABLE
        private const val STT_MAX_BACKOFF_MS = 60_000L

        @Volatile
        private var heldWifiNetwork: Network? = null

        @Volatile
        private var heldWifiSsid: String? = null

        fun currentHeldWifiNetwork(): Network? = heldWifiNetwork
    }

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var speechIntent: Intent
    private var speechRecognizerMode = MitraSpeechRecognizerConfig.MODE_DEFAULT
    private var forceDefaultSpeechRecognizer = false
    private lateinit var tts: TextToSpeech
    private lateinit var audioManager: AudioManager
    private lateinit var connectivityManager: ConnectivityManager
    private val httpClient = OkHttpClient()
    private val mainHandler = Handler(Looper.getMainLooper())

    // WebSocket for AI questions
    private var webSocket: WebSocket? = null
    private val aiBuffer = StringBuilder()
    private var isAiResponding = false

    // Frame streaming state
    private var streamingActive = false
    private var frameStreamerThread: Thread? = null
    private var frameSeq = 0
    private val streamSessionId = "sess${System.currentTimeMillis()}"
    private val frameAckInFlight = AtomicBoolean(false)
    private val pendingFrameLock = Any()
    private var pendingFrame: PendingFrame? = null
    private var wsReconnectDelayMs = 1000L
    private val maxReconnectDelayMs = 30_000L
    // Last time a cloud frame response arrived. While this is fresh, the cloud path drives navigation;
    // when it goes stale (cloud unreachable) the 1 fps loop runs local-only degraded navigation.
    @Volatile private var lastCloudResponseAtMs: Long = 0L
    private val cloudStaleThresholdMs = 3_000L
    private val localInferenceFreshMs = 3_000L
    // One in-flight frame at a time (backpressure). Time it out fast so a lost response can't stall
    // the loop — the full cloud path is ~250 ms, so anything beyond a couple seconds is gone.
    private val FRAME_ACK_TIMEOUT_MS = 2_500L
    // Delivery-ACK cursor: the server queues responses and replays everything un-ACKed on reconnect.
    // We advance its cursor by ACKing the highest delivery_seq seen, debounced, so it stops replaying
    // stale responses (the main source of multi-second lag after a reconnect).
    @Volatile private var maxDeliverySeq: Long = 0L
    @Volatile private var lastAckedDeliverySeq: Long = 0L
    // Highest delivery_seq we've spoken navigation for — older/replayed responses are acked but never
    // re-spoken (latest-frame-only behaviour).
    @Volatile private var lastSpokenDeliverySeq: Long = 0L
    private val deliveryAckRunnable = Runnable { flushDeliveryAck() }
    private val frameAckTimeoutRunnable = Runnable {
        if (frameAckInFlight.compareAndSet(true, false)) {
            synchronized(pendingFrameLock) { pendingFrame = null }
            CloudFrameResultStore.noteAckTimeout()
            Log.d("VOICE_BG", "Frame ack timeout; clearing outstanding frame")
        }
    }
    @Volatile private var localNavWaitingLogged = false
    private val localFrameProcessor: LocalFrameProcessor = object : LocalFrameProcessor {
        override fun process(bitmap: Bitmap): LocalFramePayload {
            val result = MobileInferenceState.latestFresh(localInferenceFreshMs)
            val featureResults = result?.let { hazardResultToFeatureResults(it) }.orEmpty()
            val sceneType = result?.sceneType.toWireSceneType()
            val sceneName = result.sceneName()
            val dayNight = result?.dayNight.toWireDayNight()
            return LocalFramePayload(
                meta = mapOf(
                    "local_result_available" to (result != null),
                    "local_feature_count" to featureResults.size,
                    "scene_type" to sceneType,
                    "scene_name" to sceneName,
                    "day_night" to dayNight
                ),
                sensor = defaultSensorPayload(),
                mobileFeatureOutputs = mapOf(
                    "scene_type" to sceneType,
                    "scene_name" to sceneName,
                    "day_night" to dayNight,
                    "motion_available" to false,
                    "speed_available" to false,
                    "feature_results" to featureResults
                )
            )
        }
    }

    private data class PendingFrame(val seq: Int, val timestamp: Long, val sentAt: Long)

    private data class LocalFramePayload(
        val meta: Map<String, Any> = emptyMap(),
        val sensor: Map<String, Any> = emptyMap(),
        val mobileFeatureOutputs: Map<String, Any> = mapOf("feature_results" to emptyList<Map<String, Any>>())
    )

    private interface LocalFrameProcessor {
        fun process(bitmap: Bitmap): LocalFramePayload
    }

    private class NoopFrameProcessor : LocalFrameProcessor {
        override fun process(bitmap: Bitmap): LocalFramePayload = LocalFramePayload()
    }

    // Holds the WiFi connection to MITRA_DEVICE so it survives Activity destruction
    private var wifiNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var activeWifiHoldSsid: String? = null

    private var ttsReady = false
    private var isListening = false
    private var resumeListeningAfterSpeech = false
    private var assistantActive = false
    private var waitingForFollowUpCommand = false
    // After a conversational reply (greeting/small talk), keep the mic open so the user can give a
    // command next (e.g. "hey MITRA" -> reply -> "open WhatsApp") without repeating the wake word.
    private var continueConversation = false
    // When voice control is toggled ON (mic button / default-on home page), the assistant stays
    // active so the user can speak commands directly (no wake word) continuously until turned off.
    private var directCommandMode = false
    // Last thing MITRA spoke — used by the "repeat" command.
    private var lastSpokenText: String = ""
    private var pausedByActivity = false
    // True while a phone call is ringing/active — the call owns the mic, so we pause the recognizer
    // and restart it when the call ends (otherwise it gets stuck after the call).
    @Volatile private var inCall = false

    private val callStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            when (intent?.getStringExtra(android.telephony.TelephonyManager.EXTRA_STATE)) {
                android.telephony.TelephonyManager.EXTRA_STATE_RINGING,
                android.telephony.TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    inCall = true
                    stopListening()
                    Log.d("VOICE_BG", "call active — pausing recognizer")
                }
                android.telephony.TelephonyManager.EXTRA_STATE_IDLE -> {
                    if (inCall) {
                        inCall = false
                        Log.d("VOICE_BG", "call ended — restarting recognizer")
                        mainHandler.postDelayed({
                            setupSpeechRecognizer()
                            startListening()
                        }, 1500)
                    }
                }
            }
        }
    }
    private var noMatchRetryCount = 0
    private var sttHardErrorCount = 0
    private var sttBackoffUntilMs = 0L
    private var sttRetryGeneration = 0
    private var isServiceRunning = false
    private var suppressRuntimeSpeech = false

    private val featureNameMap = mapOf(
        Feature.DAY_NIGHT to "Day Night",
        Feature.SCENE to "Scene",
        Feature.OCR to "OCR",
        Feature.FIRE_SMOKE to "Fire Smoke",
        Feature.WET_DRY to "Wet Dry",
        Feature.PEDESTRIAN to "Pedestrian",
        Feature.POTHOLE to "Pothole",
        Feature.ELECTRIC_POLE to "Electric Pole"
    )

    // Wire feature_ids per backend spec (PEDESTRIAN=4, SCENE=5, OCR=11, FIRE_SMOKE=14,
    // ELECTRIC_POLE=15, WET_DRY=16, POTHOLE=17).
    private val featureIdMap = mapOf(
        Feature.DAY_NIGHT to 1,
        Feature.PEDESTRIAN to 4,
        Feature.SCENE to 5,
        Feature.OCR to 11,
        Feature.FIRE_SMOKE to 14,
        Feature.ELECTRIC_POLE to 15,
        Feature.WET_DRY to 16,
        Feature.POTHOLE to 17
    )

    // Active wake word — loaded from prefs, updated live when user changes it
    private data class ContactMatch(
        val rawName: String,
        val normalizedName: String,
        val number: String,
        val score: Int
    )

    // ===== LIFECYCLE =====

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        promoteToForeground(includeMicrophone = false)

        ContextCompat.registerReceiver(
            this,
            callStateReceiver,
            android.content.IntentFilter("android.intent.action.PHONE_STATE"),
            ContextCompat.RECEIVER_EXPORTED
        )

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        migrateWakeWordPreference()

        // SpeechRecognizer MUST be created on the main thread — do it here in onCreate, not in any callback
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            setupSpeechRecognizer()
        }

        tts = TextToSpeech(this) { status ->
            // TTS callback may run on a background thread — always post to main thread
            mainHandler.post {
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true
                    tts.language = Locale.US
                    tts.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            mainHandler.post { stopListening() }
                        }
                        override fun onDone(utteranceId: String?) {
                            mainHandler.post {
                                when {
                                    utteranceId == "ai_response_complete" -> {
                                        isAiResponding = false
                                        resumeListeningAfterSpeech = false
                                        isListening = false
                                        startListening()
                                    }
                                    resumeListeningAfterSpeech -> {
                                        resumeListeningAfterSpeech = false
                                        restartListening()
                                    }
                                }
                            }
                        }
                        override fun onError(utteranceId: String?) {
                            mainHandler.post {
                                if (resumeListeningAfterSpeech) {
                                    resumeListeningAfterSpeech = false
                                    restartListening()
                                }
                            }
                        }
                    })
                    // Now that TTS is ready, start listening
                    startListening()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground(
            includeMicrophone = intent?.action == ACTION_ACTIVATE_VOICE ||
                intent?.action == ACTION_START_MITRA_RUNTIME ||
                intent?.action == ACTION_RESUME_LISTENING
        )

        when (intent?.action) {
            ACTION_SPEAK_TEXT -> {
                val msg = intent.getStringExtra(EXTRA_SPEAK_TEXT)
                if (!msg.isNullOrBlank()) speak(msg)
                return START_STICKY
            }

            ACTION_ACTIVATE_VOICE -> {
                // Voice control ON (mic button / default-on home page): stay active so the user can
                // speak commands directly (no wake word) continuously. Silent so it doesn't chatter
                // on every resume — the app's welcome/UI already signals that voice is on.
                cancelSelfRestart()
                pausedByActivity = false
                suppressRuntimeSpeech = false
                directCommandMode = true
                assistantActive = true
                waitingForFollowUpCommand = true
                resetSpeechErrorBackoff()
                if (ttsReady) startListening()
                return START_STICKY
            }

            ACTION_START_MITRA_RUNTIME -> {
                MitraRuntime.setActive(this, true)
                cancelSelfRestart()
                pausedByActivity = false
                suppressRuntimeSpeech = false
                sendStreamingStatus("MITRA app active")
                resetSpeechErrorBackoff()
                if (ttsReady) startListening()
                return START_STICKY
            }
            ACTION_STOP_MITRA_RUNTIME -> {
                stopMitraRuntime()
                return START_NOT_STICKY
            }
            ACTION_RESTART_RUNTIME -> {
                if (MitraRuntime.isActive(this)) {
                    cancelSelfRestart()
                    pausedByActivity = false
                    sendStreamingStatus("MITRA app recovered after restart")
                    resetSpeechErrorBackoff()
                    if (ttsReady) startListening()
                    startFrameStreaming()
                }
                return START_STICKY
            }
            ACTION_START_STREAMING -> {
                Log.d("VOICE_BG", "ACTION_START_STREAMING received")
                startFrameStreaming()
                return START_STICKY
            }
            ACTION_STOP_STREAMING -> {
                Log.d("VOICE_BG", "ACTION_STOP_STREAMING received")
                stopFrameStreaming()
                return START_STICKY
            }
            ACTION_UPDATE_WAKE_WORD -> {
                migrateWakeWordPreference()
                Log.d("VOICE_BG", "Wake word normalized to fixed runtime value: mitra")
                return START_STICKY
            }
            ACTION_PAUSE_LISTENING -> {
                directCommandMode = false
                assistantActive = false
                pausedByActivity = true
                stopListening()
                return START_STICKY
            }
            ACTION_RESUME_LISTENING -> {
                // Background wake-word mode: keep listening, but require the wake word "mitra" so
                // random background speech is not treated as a command.
                directCommandMode = false
                pausedByActivity = false
                resetSpeechErrorBackoff()
                if (ttsReady) startListening()
                return START_STICKY
            }
            ACTION_HOLD_WIFI -> {
                val ssid = intent.getStringExtra("ssid")
                val password = intent.getStringExtra("password")
                if (!ssid.isNullOrBlank() && !password.isNullOrBlank()) {
                    holdWifiConnection(ssid, password)
                }
                return START_STICKY
            }
        }
        if (wifiNetworkCallback == null) {
            // Service restarted by system (after boot/kill) — reconnect with saved creds
            val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            val savedSsid = prefs.getString(MainActivity.KEY_SSID, null)
            val savedPassword = prefs.getString(MainActivity.KEY_PASSWORD, null)
            if (!savedSsid.isNullOrBlank() && !savedPassword.isNullOrBlank()) {
                holdWifiConnection(savedSsid, savedPassword)
            }
        }
        return START_STICKY
    }

    private fun startFrameStreaming() {
        if (streamingActive) return
        streamingActive = true
        CloudFrameResultStore.resetTelemetry("streaming-started")
        Log.d("VOICE_BG", "Starting frame streaming")
        sendStreamingStatus("Streaming started")
        frameStreamerThread = Thread { frameStreamLoop() }.apply { start() }
    }

    private fun stopFrameStreaming() {
        streamingActive = false
        frameStreamerThread?.interrupt()
        frameStreamerThread = null
        frameAckInFlight.set(false)
        mainHandler.removeCallbacks(frameAckTimeoutRunnable)
        CloudFrameResultStore.noteStatus("streaming-stopped")
        sendStreamingStatus("Streaming stopped")
        Log.d("VOICE_BG", "Stopped frame streaming")
    }

    private fun sendStreamingStatus(message: String) {
        CloudFrameResultStore.noteStatus(message)
        val intent = Intent(ACTION_STREAMING_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS_MESSAGE, message)
        }
        sendBroadcast(intent)
    }

    private fun migrateWakeWordPreference() {
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val previous = prefs.getString(MainActivity.KEY_WAKE_WORD, null)
        if (previous != "mitra") {
            prefs.edit().putString(MainActivity.KEY_WAKE_WORD, "mitra").apply()
            Log.d("VOICE_BG", "Migrated wake word preference from ${previous ?: "<unset>"} to mitra")
        }
    }

    private fun restartServiceIntent(): PendingIntent {
        val intent = Intent(this, BackgroundService::class.java).apply {
            action = ACTION_RESTART_RUNTIME
        }
        return PendingIntent.getService(
            this,
            RESTART_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun scheduleSelfRestart() {
        val alarmManager = getSystemService(AlarmManager::class.java)
        alarmManager?.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + 1500L,
            restartServiceIntent()
        )
    }

    private fun cancelSelfRestart() {
        val alarmManager = getSystemService(AlarmManager::class.java)
        alarmManager?.cancel(restartServiceIntent())
    }

    private fun stopMitraRuntime() {
        Log.d("VOICE_BG", "Stopping MITRA runtime")
        MitraRuntime.setActive(this, false)
        cancelSelfRestart()
        pausedByActivity = true
        assistantActive = false
        waitingForFollowUpCommand = false
        resumeListeningAfterSpeech = false
        stopListening()
        stopFrameStreaming()
        releaseWifiHold()
        stopForeground(true)
        stopSelf()
    }

    private fun frameStreamLoop() {
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val deviceId = prefs.getString(MainActivity.KEY_UNIQUE_CODE, null)
        val cloudUploadEnabled = !deviceId.isNullOrBlank()
        if (!cloudUploadEnabled) {
            Log.w("VOICE_BG", "No device id available for frame streaming; local-only navigation remains active")
        }
        while (streamingActive && isServiceRunning) {
            try {
                if (frameAckInFlight.get()) {
                    maybeRunLocalNav()
                    Thread.sleep(250)
                    continue
                }

                val startTime = System.currentTimeMillis()
                val bitmap = requestScreenBitmap(2000)
                if (bitmap == null) {
                    Log.w("VOICE_BG", "No video frame available")
                    Thread.sleep(500)
                    continue
                }

                val width = bitmap.width
                val height = bitmap.height
                val localPayload = localFrameProcessor.process(bitmap)

                // Cloud reachability == the WebSocket being open. Do NOT probe the Pi/RTSP host here:
                // that is a different network path and a blocking socket connect would stall the send
                // loop by up to its timeout on every frame.
                if (cloudUploadEnabled && webSocket == null) {
                    connectWebSocketIfNeeded()
                }
                val ws = webSocket
                if (cloudUploadEnabled && ws != null) {
                    val jpeg = compressBitmapToJpeg(bitmap, 70)
                    bitmap.recycle()
                    if (jpeg == null) {
                        Log.e("VOICE_BG", "JPEG compression failed")
                        Thread.sleep(500)
                        continue
                    }
                    val sendStart = System.currentTimeMillis()
                    val currentSeq = ++frameSeq
                    val sent = doSendFrame(deviceId!!, currentSeq, width, height, jpeg, localPayload)
                    val sendLatency = System.currentTimeMillis() - sendStart
                    if (sent) {
                        CloudFrameResultStore.noteFrameSent(currentSeq)
                        Log.d("VOICE_BG", "frame_sent seq=$currentSeq jpeg_size=${jpeg.size} send_latency=${sendLatency}ms")
                        frameAckInFlight.set(true)
                        mainHandler.postDelayed(frameAckTimeoutRunnable, FRAME_ACK_TIMEOUT_MS)
                    } else {
                        CloudFrameResultStore.noteFrameSendFailed()
                        Log.w("VOICE_BG", "frame_send_failed seq=$currentSeq jpeg_size=${jpeg.size} send_latency=${sendLatency}ms")
                    }
                } else if (cloudUploadEnabled) {
                    bitmap.recycle()
                    CloudFrameResultStore.noteStatus("WebSocket disconnected")
                    Log.d("VOICE_BG", "Cloud WS not connected; local inference continues without upload")
                } else {
                    bitmap.recycle()
                }

                maybeRunLocalNav()

                val frameIntervalMs = getFrameIntervalMs()
                val elapsed = System.currentTimeMillis() - startTime
                val sleepTime = frameIntervalMs - elapsed
                if (sleepTime > 0) Thread.sleep(sleepTime)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                Log.e("VOICE_BG", "Frame stream loop error: ${e.message}")
                Thread.sleep(500)
            }
        }
    }

    private fun requestScreenBitmap(timeoutMs: Long): Bitmap? {
        // Read the canonical frame bus directly (single source of truth); no UI/Activity coupling.
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val bitmap = VideoFrameCache.acquireLatestCopy(CLOUD_FRAME_MAX_AGE_MS)
            if (bitmap != null) return bitmap
            Thread.sleep(100)
        }
        return null
    }

    /**
     * Degraded navigation when the cloud is unreachable: reason from local data only (scene gate +
     * local hazards/pedestrian, zone-based) and speak. Suppressed while the cloud path is fresh so the
     * two never double-speak. Runs at the 1 fps frame-loop cadence.
     */
    private fun maybeRunLocalNav() {
        if (System.currentTimeMillis() - lastCloudResponseAtMs < cloudStaleThresholdMs) return
        val local = MobileInferenceState.latestFresh(localInferenceFreshMs)
        if (local == null) {
            if (!localNavWaitingLogged) {
                localNavWaitingLogged = true
                Log.d("VOICE_BG", "Local-only nav waiting for fresh local inference result")
            }
            return
        }
        localNavWaitingLogged = false
        val nav = AndroidNavigationModule.evaluate("local-${local.frameId}", null, local, emptyMap()) ?: return
        NavigationStateStore.update(nav)
        val speechGate = NavigationStateStore.evaluateSpeech(nav)
        if (speechGate.shouldSpeak) {
            Log.d("VOICE_BG", "Local-only nav TTS spoken reason=${speechGate.reason} action=${nav.finalAction} text=${nav.finalText}")
            if (!suppressRuntimeSpeech) {
                mainHandler.post { speak(nav.finalText) }
            }
        } else {
            Log.d("VOICE_BG", "Local-only nav TTS skipped reason=${speechGate.reason} action=${nav.finalAction} text=${nav.finalText}")
        }
    }

    /** Record the highest delivery_seq from a response and schedule a debounced ACK to the server. */
    private fun noteDeliverySeq(seq: Long?) {
        if (seq == null || seq <= 0) return
        if (seq > maxDeliverySeq) maxDeliverySeq = seq
        mainHandler.removeCallbacks(deliveryAckRunnable)
        mainHandler.postDelayed(deliveryAckRunnable, 250)
    }

    /** Send {type:"ack", last_seq} so the server advances its replay cursor and stops re-sending. */
    private fun flushDeliveryAck() {
        val seq = maxDeliverySeq
        if (seq <= lastAckedDeliverySeq) return
        val ws = webSocket ?: return
        try {
            val packer = MessagePack.newDefaultBufferPacker()
            packer.packMapHeader(2)
            packer.packString("type"); packer.packString("ack")
            packer.packString("last_seq"); packer.packLong(seq)
            packer.close()
            if (ws.send(packer.toByteArray().toByteString())) {
                lastAckedDeliverySeq = seq
                Log.d("VOICE_BG", "sent delivery ack last_seq=$seq")
            }
        } catch (e: Exception) {
            Log.w("VOICE_BG", "delivery ack send failed: ${e.message}")
        }
    }

    /**
     * True if this response is a stale/replayed one we've already spoken past. We still ACK it (to
     * advance the cursor) but must not re-speak it. Returns false (fresh) when there is no seq.
     */
    private fun isStaleDelivery(deliverySeq: Long?): Boolean {
        if (deliverySeq == null) return false
        return deliverySeq <= lastSpokenDeliverySeq
    }

    /** Logs what the cloud actually returned so we can verify GPU2/GPU3 features reach the app. */
    private fun logCloudFeatures(outer: Map<String, Any?>, inner: Map<String, Any?>) {
        try {
            val data = outer["data"]
            val dataDesc = when (data) {
                is ByteArray -> "bytes[${data.size}]"
                is okio.ByteString -> "bytestring[${data.size}]"
                is Map<*, *> -> "map[${data.size}]"
                is String -> "string[${data.length}]"
                null -> "null"
                else -> data.javaClass.simpleName
            }
            Log.d(
                "VOICE_BG",
                "cloud_outer keys=${outer.keys} status=${outer["status"]} rt=${outer["response_type"]} " +
                    "frame_id=${outer["frame_id"]} msg=${outer["message"]} data=$dataDesc innerKeys=${inner.keys}"
            )
            if (inner.isNotEmpty()) {
                Log.d("VOICE_BG", "cloud_inner=${inner.toString().take(600)}")
            }
        } catch (e: Exception) {
            Log.w("VOICE_BG", "logCloudFeatures failed: ${e.message}")
        }
    }

    private fun compressBitmapToJpeg(bitmap: Bitmap, quality: Int): ByteArray? {
        return try {
            ByteArrayOutputStream().use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) return null
                output.toByteArray()
            }
        } catch (e: Exception) {
            Log.e("VOICE_BG", "JPEG compression failed: ${e.message}")
            null
        }
    }

    private fun doSendFrame(
        deviceId: String,
        seq: Int,
        width: Int,
        height: Int,
        jpegBytes: ByteArray,
        localPayload: LocalFramePayload
    ): Boolean {
        val ws = webSocket ?: return false
        return try {
            val timestampSeconds = System.currentTimeMillis() / 1000.0
            val timestampMillis = (timestampSeconds * 1000.0).toLong()
            val sanitizedDeviceId = sanitizeDeviceId(deviceId)
            synchronized(pendingFrameLock) {
                pendingFrame = PendingFrame(seq, timestampMillis, timestampMillis)
            }
            val packer = MessagePack.newDefaultBufferPacker().apply {
                packMapHeader(10)
                packString("protocol"); packString("mitra.frame.v1")
                packString("device_id"); packString(sanitizedDeviceId)
                packString("session_id"); packString(streamSessionId)
                packString("timestamp"); packDouble(timestampSeconds)
                packString("seq"); packInt(seq)
                packString("frame_index"); packInt(seq)
                packString("frame"); packBinaryHeader(jpegBytes.size); writePayload(jpegBytes)
                packString("meta")
                // scene_type and day_night are written explicitly below, so exclude them from the
                // extra-meta count — otherwise the declared map header overcounts and the server
                // gets "incomplete input" and rejects the whole frame.
                val extraMetaCount = localPayload.meta.keys.count { it != "scene_type" && it != "day_night" }
                packMapHeader(8 + extraMetaCount)
                packString("width"); packInt(width)
                packString("height"); packInt(height)
                packString("fps"); packInt(getStreamingFps())
                packString("format"); packString("jpeg")
                packString("device_type"); packString("android")
                packString("source"); packString("hardware_camera")
                packString("scene_type"); packString((localPayload.meta["scene_type"] as? String) ?: "outdoor")
                packString("day_night"); packString((localPayload.meta["day_night"] as? String) ?: "day")
                for ((key, value) in localPayload.meta) {
                    if (key == "scene_type" || key == "day_night") continue
                    packString(key)
                    packAny(this, value)
                }
                packString("sensor")
                packAny(this, localPayload.sensor)
                packString("mobile_feature_outputs")
                packAny(this, localPayload.mobileFeatureOutputs)
            }
            packer.close()
            val sent = ws.send(packer.toByteArray().toByteString())
            if (!sent) {
                synchronized(pendingFrameLock) { pendingFrame = null }
            }
            sent
        } catch (e: Exception) {
            Log.e("VOICE_BG", "Failed to send frame: ${e.message}")
            synchronized(pendingFrameLock) { pendingFrame = null }
            false
        }
    }

    private fun packAny(packer: org.msgpack.core.MessagePacker, value: Any?) {
        when (value) {
            null -> packer.packNil()
            is Int -> packer.packInt(value)
            is Long -> packer.packLong(value)
            is Float -> packer.packFloat(value)
            is Double -> packer.packDouble(value)
            is Boolean -> packer.packBoolean(value)
            is String -> packer.packString(value)
            is Map<*, *> -> {
                packer.packMapHeader(value.size)
                value.forEach { (k, v) ->
                    packer.packString(k.toString())
                    packAny(packer, v)
                }
            }
            is List<*> -> {
                packer.packArrayHeader(value.size)
                value.forEach { item -> packAny(packer, item) }
            }
            else -> packer.packString(value.toString())
        }
    }

    private fun getWebSocketUrl(deviceId: String): String {
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val overrideEndpoint = prefs.getString(MainActivity.KEY_STREAMING_ENDPOINT, null)
            ?.takeIf { it.isNotBlank() }
        val endpoint = overrideEndpoint ?: getString(R.string.streaming_endpoint)
        return if (endpoint.endsWith("/")) "$endpoint$deviceId" else "$endpoint/$deviceId"
    }

    private fun getStreamingFps(): Int {
        return 1
    }

    private fun getFrameIntervalMs(): Long = 1000L / getStreamingFps()

    private fun scheduleReconnect() {
        val delay = wsReconnectDelayMs.coerceIn(1000L, maxReconnectDelayMs)
        Log.d("VOICE_BG", "Scheduling WebSocket reconnect in ${delay}ms")
        mainHandler.postDelayed({
            if (isServiceRunning) connectWebSocketIfNeeded()
        }, delay)
        wsReconnectDelayMs = (wsReconnectDelayMs * 2).coerceAtMost(maxReconnectDelayMs)
    }

    private fun resetReconnectDelay() {
        wsReconnectDelayMs = 1000L
    }


    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        try { unregisterReceiver(callStateReceiver) } catch (_: Exception) {}
        releaseWifiHold()
        webSocket?.close(1000, "Service stopped")
        cancelSelfRestart()
        if (::speechRecognizer.isInitialized) speechRecognizer.destroy()
        if (::tts.isInitialized) tts.shutdown()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (MitraRuntime.isActive(this)) {
            scheduleSelfRestart()
            Log.d("VOICE_BG", "Task removed while MITRA app is active; scheduled restart")
        }
    }

    // ===== NOTIFICATION =====

    private fun promoteToForeground(includeMicrophone: Boolean = false) {
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val mediaOnlyType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            val requestedType = if (includeMicrophone && hasRecordAudioPermission()) {
                mediaOnlyType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                mediaOnlyType
            }
            try {
                startForeground(NOTIFICATION_ID, notification, requestedType)
                return
            } catch (e: SecurityException) {
                Log.w("VOICE_BG", "Microphone foreground type not allowed now; using media-only foreground", e)
                if (requestedType != mediaOnlyType) {
                    startForeground(NOTIFICATION_ID, notification, mediaOnlyType)
                    return
                }
                throw e
            }
        }
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            RESTART_REQUEST_CODE + 1,
            Intent(this, BackgroundService::class.java).apply {
                action = ACTION_STOP_MITRA_RUNTIME
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("MITRA app")
            .setContentText("MITRA app running and listening")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop MITRA", stopIntent).build())
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "MITRA app service", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps MITRA app running in background"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }


    // ===== SPEECH RECOGNITION =====

    private fun setupSpeechRecognizer() {
        // Destroy old instance if it exists before creating a new one
        try { speechRecognizer.destroy() } catch (_: Exception) {}

        val created = MitraSpeechRecognizerConfig.create(this, forceDefaultSpeechRecognizer, "VOICE_BG")
        speechRecognizer = created.recognizer
        speechRecognizerMode = created.mode

        speechIntent = MitraSpeechRecognizerConfig.commandIntent(
            maxResults = 5,
            possiblyCompleteSilenceMs = 5000L,
            completeSilenceMs = 7000L
        )
        Log.i(
            "VOICE_BG",
            "SpeechRecognizer mode=$speechRecognizerMode locale=${created.languageTag} preferOffline=${created.preferOffline}"
        )

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                isListening = false
                noMatchRetryCount = 0
                resetSpeechErrorBackoff()
                val candidates = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?: arrayListOf()
                val text = pickBestCandidate(candidates)
                val normalized = normalizeSpeechText(text)
                Log.d("VOICE_BG", "Heard: $text | candidates=$candidates | Normalized: $normalized")
                handleSpeechResult(normalized)
            }

            override fun onError(error: Int) {
                isListening = false
                Log.d("VOICE_BG", "STT error code: $error")

                val fallbackFromOnDevice =
                    speechRecognizerMode == MitraSpeechRecognizerConfig.MODE_ON_DEVICE &&
                    MitraSpeechRecognizerConfig.shouldFallbackFromOnDevice(error)
                if (fallbackFromOnDevice) {
                    forceDefaultSpeechRecognizer = true
                    Log.w(
                        "VOICE_BG",
                        "on-device recognizer failed error=$error; falling back to default offline-preferred recognizer"
                    )
                }

                when (error) {
                    // These errors mean the recognizer is broken or busy. Back off so the hardware
                    // mic does not audibly toggle on/off in a tight loop.
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                    SpeechRecognizer.ERROR_CLIENT -> {
                        scheduleSpeechRetry(error, recreate = true)
                    }
                    STT_LANGUAGE_PACK_ERROR_CODE -> {
                        scheduleSpeechRetry(error, recreate = fallbackFromOnDevice)
                    }
                    STT_LANGUAGE_UNAVAILABLE_ERROR_CODE -> {
                        scheduleSpeechRetry(error, recreate = fallbackFromOnDevice)
                    }
                    // No speech / silence — retry during command phase, else restart
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        if (assistantActive && noMatchRetryCount < 2) {
                            noMatchRetryCount++
                            scheduleListeningRetry(if (waitingForFollowUpCommand) 900L else 1500L)
                        } else {
                            noMatchRetryCount = 0
                            if (assistantActive) {
                                assistantActive = false
                                waitingForFollowUpCommand = false
                            }
                            if (!resumeListeningAfterSpeech) restartListening()
                        }
                    }
                    // Network / server errors — back off; the phone may be on MITRA_DEVICE with no internet.
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                    SpeechRecognizer.ERROR_SERVER -> {
                        scheduleSpeechRetry(error, recreate = fallbackFromOnDevice)
                    }
                    // Any other error — recreate with backoff to be safe.
                    else -> {
                        scheduleSpeechRetry(error, recreate = true)
                    }
                }
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun resetSpeechErrorBackoff() {
        sttHardErrorCount = 0
        sttBackoffUntilMs = 0L
    }

    private fun scheduleSpeechRetry(error: Int, recreate: Boolean) {
        sttHardErrorCount = (sttHardErrorCount + 1).coerceAtMost(8)
        val delayMs = when (sttHardErrorCount) {
            1 -> 1_500L
            2 -> 5_000L
            3 -> 15_000L
            4 -> 30_000L
            else -> STT_MAX_BACKOFF_MS
        }
        sttBackoffUntilMs = SystemClock.elapsedRealtime() + delayMs
        Log.w(
            "VOICE_BG",
            "Speech recognizer error=$error; retrying in ${delayMs}ms (hardErrors=$sttHardErrorCount)"
        )
        scheduleListeningRetry(delayMs, recreate)
    }

    private fun scheduleListeningRetry(delayMs: Long, recreate: Boolean = false) {
        val generation = ++sttRetryGeneration
        mainHandler.postDelayed({
            if (generation != sttRetryGeneration || pausedByActivity || inCall || resumeListeningAfterSpeech) {
                return@postDelayed
            }
            if (recreate) {
                setupSpeechRecognizer()
            }
            startListening()
        }, delayMs)
    }

    private fun handleSpeechResult(normalizedText: String) {
        if (!assistantActive) {
            if (containsWakeWord(normalizedText)) {
                val commandAfterWake = removeWakeWord(normalizedText)
                suppressRuntimeSpeech = false
                assistantActive = true
                if (commandAfterWake.isEmpty()) {
                    waitingForFollowUpCommand = true
                    playAck()
                    return
                } else {
                    processCommand(commandAfterWake)
                    applyConversationState()
                }
            }
        } else {
            val followUp = normalizedText.trim()
            if (followUp.isEmpty()) {
                playAck()
                return
            } else {
                processCommand(followUp)
                applyConversationState()
            }
        }
        if (!resumeListeningAfterSpeech) restartListening()
    }

    /**
     * After a command, decide whether to keep the assistant active. A conversational reply
     * (greeting / small talk) keeps the mic open for a follow-up command; anything else ends the turn.
     */
    private fun applyConversationState() {
        if (continueConversation || directCommandMode) {
            continueConversation = false
            assistantActive = true
            waitingForFollowUpCommand = true
        } else {
            assistantActive = false
            waitingForFollowUpCommand = false
        }
    }

    private fun startListening() {
        if (isListening || pausedByActivity || inCall) return
        if (!::speechRecognizer.isInitialized) {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) return
            setupSpeechRecognizer()
        }
        val now = SystemClock.elapsedRealtime()
        if (now < sttBackoffUntilMs) {
            scheduleListeningRetry(sttBackoffUntilMs - now)
            return
        }
        isListening = true
        mainHandler.postDelayed({
            if (isListening) {
                try {
                    speechRecognizer.startListening(speechIntent)
                } catch (e: SecurityException) {
                    Log.w("VOICE_BG", "Microphone start blocked by Android foreground rules", e)
                    isListening = false
                } catch (e: Exception) {
                    Log.w("VOICE_BG", "Speech recognizer start failed: ${e.message}", e)
                    isListening = false
                }
            }
        }, 50)
    }

    private fun stopListening() {
        sttRetryGeneration++
        if (!isListening) return
        try { speechRecognizer.stopListening() } catch (_: Exception) {}
        isListening = false
    }

    private fun restartListening() {
        scheduleListeningRetry(if (waitingForFollowUpCommand) 600L else 900L)
    }

    private fun muteAudio() {
        // Only mute notification/system beeps — NOT STREAM_MUSIC (that's TTS output)
        audioManager.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0)
        audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0)
    }

    private fun unmuteAudio() {
        audioManager.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0)
        audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0)
    }

    // ===== COMMAND PROCESSING =====

    /** From the recognizer's guesses, prefer the first that looks like a command; else the top guess. */
    private fun pickBestCandidate(candidates: List<String>): String =
        VoiceCommandSelector.pickBestCandidate(candidates)

    private fun normalizeSpeechText(text: String) =
        VoiceCommandSelector.normalizeSpeechText(text)

    private fun containsWakeWord(text: String): Boolean =
        VoiceCommandSelector.containsWakeWord(text)

    private fun removeWakeWord(text: String): String =
        VoiceCommandSelector.removeWakeWord(text)

    private fun normalizeCommand(command: String): String {
        return command.lowercase()
            // WhatsApp variants
            .replace("what's app", "whatsapp")
            .replace("whats app", "whatsapp")
            .replace("what sap", "whatsapp")
            .replace("what's up", "whatsapp")
            .replace("whats up", "whatsapp")
            .replace("watsap", "whatsapp")
            .replace("watsapp", "whatsapp")
            .replace("whatsap", "whatsapp")
            .replace("whatsappp", "whatsapp")
            .replace("what app", "whatsapp")
            // YouTube variants — use word boundaries so "utube" inside "youtube" is not doubled.
            .replace("you tube", "youtube")
            .replace(Regex("\\bu tube\\b"), "youtube")
            .replace(Regex("\\butube\\b"), "youtube")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun isWhatsAppCommand(text: String): Boolean {
        val compact = text.replace(" ", "")
        return text.contains("whatsapp") ||
            compact.contains("whatsapp") ||
            (text.contains("what") && text.contains("app")) ||
            text.contains("watsap") ||
            text.contains("watsapp")
    }

    private fun isYouTubeCommand(text: String): Boolean {
        return text.contains("youtube") || text.contains("you tube") ||
            text.contains("utube") || text.contains("u tube")
    }

    private fun processCommand(command: String) {
        var normalized = normalizeCommand(command)
            // Common mis-hears of the preposition "to" (e.g. "send hello tu shabaz").
            .replace(" tu ", " to ").replace(" too ", " to ").replace(" 2 ", " to ")
        // In direct-command mode the wake word stays in the phrase ("mitra send ... to ...").
        // Strip a leading wake word (and its mis-hears) so "startsWith" commands still match.
        for (w in listOf("mitra ", "mithra ", "mi tra ", "myntra ", "metro ", "mantra ", "meetra ")) {
            if (normalized.startsWith(w)) { normalized = normalized.removePrefix(w).trim(); break }
        }
        Log.d("VOICE_BG", "Command raw: $command | normalized: $normalized")

        when {
            // WhatsApp open
            (normalized.contains("open") || normalized.contains("launch")) && isWhatsAppCommand(normalized) ->
                openApp("com.whatsapp")

            // YouTube open
            (normalized.contains("open") || normalized.contains("launch")) && isYouTubeCommand(normalized) ->
                openApp("com.google.android.youtube")

            // Generic "open <app name>" — search all installed apps
            normalized.startsWith("open ") || normalized.startsWith("launch ") -> {
                val appName = normalized
                    .removePrefix("launch ")
                    .removePrefix("open ")
                    .trim()
                if (appName.isEmpty()) speak("Tell me which app to open")
                else openAppByName(appName)
            }

            normalized.startsWith("call ") || normalized.startsWith("call to ") ||
            normalized.startsWith("give a call to ") || normalized.startsWith("make a call to ") -> {
                val name = normalized
                    .removePrefix("make a call to ")
                    .removePrefix("give a call to ")
                    .removePrefix("call to ")
                    .removePrefix("call ")
                    .trim()
                if (name.isEmpty()) speak("Tell me who to call") else callContact(name)
            }

            normalized.contains("play") && isYouTubeCommand(normalized) -> {
                val query = normalized.substringAfter("play")
                    .replace("in youtube", "").replace("on youtube", "")
                    .replace("in utube", "").replace("on utube", "")
                    .replace("song", "").trim()
                if (query.isEmpty()) speak("Tell me what to play") else playOnYouTube(query)
            }

            // Send a WhatsApp message: "send <message> to <name>" (WhatsApp is the default).
            normalized.startsWith("send ") && normalized.contains(" to ") -> {
                val body = normalized.removePrefix("send").trimStart()
                    .removePrefix("message").removePrefix("a message").trim()
                val msg = body.substringBefore(" to ").trim()
                var name = body.substringAfter(" to ").trim()
                name = listOf("on whatsapp", "in whatsapp", "through whatsapp",
                    "whatsapp", "what's app", "whats app", "what app")
                    .fold(name) { acc, w -> acc.substringBefore(w) }.trim()
                if (msg.isBlank() || name.isBlank()) speak("Tell me the message and the contact name.")
                else { speak("Sending message to $name"); sendWhatsAppMessage(name, msg) }
            }

            normalized.startsWith("navigate to ") || normalized.startsWith("go to ") -> {
                val dest = normalized.removePrefix("navigate to ").removePrefix("go to ").trim()
                if (dest.isEmpty()) speak("Tell me where to navigate") else navigateTo(dest)
            }

            normalized.contains("read text") -> {
                speak("Reading text")
                readTextFromScreen()
            }

            normalized.contains("take picture") || normalized.contains("click picture") ||
            normalized.contains("take photo") || normalized.contains("capture") ||
            normalized.contains("take a picture") || normalized.contains("click a picture") ||
            normalized.contains("take a photo") -> {
                speak("Taking picture")
                sendBroadcast(Intent(ACTION_TAKE_PICTURE).setPackage(packageName))
            }

            // Close current app / go to home screen
            normalized == "close" || normalized == "close app" || normalized == "close the app" ||
            normalized == "go home" || normalized == "home" || normalized == "minimize" ||
            normalized == "exit" || normalized == "close it" -> {
                if (AutoSendAccessibilityService.goHome()) speak("Going to the home screen.")
                else speak("Please enable accessibility for MITRA to close apps.")
            }

            // Go back
            normalized == "go back" || normalized == "back" -> {
                AutoSendAccessibilityService.goBack()
            }

            // Flashlight / torch
            normalized.contains("flashlight on") || normalized.contains("torch on") ||
            normalized == "light on" -> setTorch(true)
            normalized.contains("flashlight off") || normalized.contains("torch off") ||
            normalized == "light off" -> setTorch(false)

            // Volume
            normalized.contains("volume up") || normalized.contains("increase volume") ||
            normalized.contains("louder") -> {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                speak("Volume up.")
            }
            normalized.contains("volume down") || normalized.contains("decrease volume") ||
            normalized.contains("lower volume") -> {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                speak("Volume down.")
            }
            normalized == "mute" || normalized.contains("mute volume") -> {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                speak("Muted.")
            }
            normalized == "unmute" || normalized.contains("unmute volume") -> {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                speak("Unmuted.")
            }

            // System actions (via accessibility)
            normalized == "lock" || normalized.contains("lock screen") || normalized.contains("lock phone") -> {
                if (!AutoSendAccessibilityService.lockScreen())
                    speak("Please enable accessibility to lock the screen.")
            }
            normalized.contains("screenshot") -> {
                if (AutoSendAccessibilityService.takeScreenshot()) speak("Screenshot taken.")
                else speak("Screenshot is not available.")
            }
            normalized.contains("recent apps") || normalized == "recents" ->
                AutoSendAccessibilityService.openRecents()
            normalized.contains("open notification") || normalized == "notifications" ->
                AutoSendAccessibilityService.openNotifications()
            normalized.contains("quick settings") ->
                AutoSendAccessibilityService.openQuickSettings()

            // Reply to the last message read aloud
            normalized.startsWith("reply ") || normalized == "reply" -> {
                val msg = normalized.removePrefix("reply").trim()
                if (msg.isBlank()) speak("What should I reply?") else replyToLastMessage(msg)
            }

            // Time
            normalized.contains("the time") || normalized == "time" || normalized.contains("what time") -> {
                val t = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                    .format(java.util.Date())
                speak("The time is $t.")
            }

            // Date
            normalized.contains("the date") || normalized == "date" || normalized.contains("what day") -> {
                val d = java.text.SimpleDateFormat("EEEE, MMMM d", java.util.Locale.getDefault())
                    .format(java.util.Date())
                speak("Today is $d.")
            }

            // Battery level
            normalized.contains("battery") -> {
                val bm = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
                val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                if (level in 0..100) speak("Battery is at $level percent.")
                else speak("Sorry, I could not read the battery level.")
            }

            // Repeat last response
            normalized == "repeat" || normalized.contains("say again") ||
            normalized.contains("repeat that") || normalized.contains("come again") -> {
                if (lastSpokenText.isNotBlank()) speak(lastSpokenText)
                else speak("I have nothing to repeat yet.")
            }

            // Stop / sleep — stop responding to direct commands but keep the wake word active.
            normalized == "stop" || normalized == "sleep" || normalized == "stop listening" ||
            normalized.contains("go to sleep") -> {
                directCommandMode = false
                assistantActive = false
                waitingForFollowUpCommand = false
                continueConversation = false
                speak("Going to sleep. Say MITRA to wake me.")
            }

            // "start mitra" / "start" — begin the MITRA search flow (Wi-Fi/hardware or camera).
            // "start m..." also catches common mis-hears of "mitra" (myntra, metro, mantra, mithra).
            normalized == "start" || normalized == "begin" || normalized == "start search" ||
            normalized.startsWith("start m") -> {
                assistantActive = false
                waitingForFollowUpCommand = false
                continueConversation = false
                speak("Starting MITRA.")
                // Bring the app to the foreground and begin the search — works even when the app is
                // only in recents / the user is on the phone home screen.
                val launch = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    action = MainActivity.ACTION_START_SEARCH
                }
                if (!AutoSendAccessibilityService.tryLaunch(launch)) {
                    try { startActivity(launch) } catch (e: Exception) {
                        Log.w("VOICE_BG", "could not launch MainActivity for start: ${e.message}")
                        sendBroadcast(Intent(ACTION_VOICE_START_SEARCH).setPackage(packageName))
                    }
                }
            }

            // Wake word said alone (or a mis-hear of it) — acknowledge instead of "not available".
            normalized == "mitra" || normalized == "mithra" || normalized == "mi tra" ||
            normalized == "myntra" || normalized == "metro" || normalized == "mantra" ||
            normalized == "meetra" || normalized == "mitra mitra" ->
                conversationalReply("Yes, I'm listening.")

            // ===== Conversational / small talk (two-way chatbot feel) =====
            // Speech-to-text often mishears "hey" as "he"/"hi"/"hai", so accept those too.
            normalized.isEmpty() || normalized == "hey" || normalized == "he" ||
            normalized == "hi" || normalized == "hii" || normalized == "hai" ||
            normalized == "hello" || normalized == "helo" || normalized == "yo" ||
            normalized.startsWith("hey ") || normalized.startsWith("he ") ||
            normalized.startsWith("hi ") || normalized.startsWith("hello ") ->
                conversationalReply("Hey! I'm here. How can I help you?")

            normalized.contains("how are you") ->
                conversationalReply("I'm doing great, thank you. I'm ready to help you.")

            normalized.contains("who are you") || normalized.contains("your name") ||
            normalized.contains("what are you") ->
                conversationalReply("I am MITRA, your personal assistant. I help you see and get around.")

            normalized.contains("what can you do") || normalized.contains("help me") ||
            normalized == "help" ->
                conversationalReply("I can open apps, make calls, send WhatsApp messages, read text, " +
                    "take pictures, and describe what is around you. Just tell me what you need.")

            normalized.contains("thank") ->
                conversationalReply("You're welcome. I'm always here to help.")

            normalized.contains("good morning") || normalized.contains("good evening") ||
            normalized.contains("good night") ->
                conversationalReply("Hello! I hope you're having a good day. How can I help?")

            else -> {
                assistantActive = false
                waitingForFollowUpCommand = false
                isAiResponding = false
                aiBuffer.clear()
                speak("That action is not available in MITRA app right now.")
            }
        }
    }

    /** Sends [message] as a reply to the last message that was read aloud (via its RemoteInput). */
    private fun replyToLastMessage(message: String) {
        val action = MitraNotificationListener.lastReplyAction
        val inputs = action?.remoteInputs
        if (action == null || inputs.isNullOrEmpty()) {
            speak("There is no message to reply to.")
            return
        }
        try {
            val intent = Intent()
            val results = Bundle()
            for (ri in inputs) results.putCharSequence(ri.resultKey, message)
            android.app.RemoteInput.addResultsToIntent(inputs, intent, results)
            action.actionIntent.send(this, 0, intent)
            speak("Reply sent to ${MitraNotificationListener.lastSender ?: "them"}.")
        } catch (e: Exception) {
            Log.w("VOICE_BG", "reply failed: ${e.message}")
            speak("Sorry, I could not send the reply.")
        }
    }

    /** Turns the phone flashlight/torch on or off. */
    private fun setTorch(on: Boolean) {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (id == null) { speak("This phone has no flashlight."); return }
            cm.setTorchMode(id, on)
            speak(if (on) "Flashlight on." else "Flashlight off.")
        } catch (e: Exception) {
            Log.w("VOICE_BG", "torch failed: ${e.message}")
            speak("Could not toggle the flashlight.")
        }
    }

    /** Speaks a conversational reply and keeps the mic open for the user's next command. */
    private fun conversationalReply(message: String) {
        isAiResponding = false
        aiBuffer.clear()
        continueConversation = true
        speak(message)
    }

    // ===== WIFI HOLD =====

    @SuppressLint("MissingPermission")
    private fun holdWifiConnection(ssid: String, password: String) {
        val normalizedSsid = ssid.trim()
        if (normalizedSsid.isBlank()) return
        if (activeWifiHoldSsid == normalizedSsid && wifiNetworkCallback != null) {
            Log.d("VOICE_BG", "WiFi hold: already requesting/holding $normalizedSsid")
            return
        }
        if (heldWifiNetwork != null && heldWifiSsid == normalizedSsid) {
            Log.d("VOICE_BG", "WiFi hold: already connected to $normalizedSsid")
            return
        }

        // Null out the field BEFORE unregistering so onLost() from the old callback
        // sees wifiNetworkCallback == null and does NOT schedule another retry.
        val oldCallback = wifiNetworkCallback
        wifiNetworkCallback = null
        activeWifiHoldSsid = null
        oldCallback?.let {
            try { connectivityManager.unregisterNetworkCallback(it) } catch (_: Exception) { }
        }

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(normalizedSsid)
            .setWpa2Passphrase(password)
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val newCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                heldWifiNetwork = network
                heldWifiSsid = normalizedSsid
                try { connectivityManager.bindProcessToNetwork(network) } catch (_: Exception) { }
                Log.d("VOICE_BG", "WiFi hold: connected to $normalizedSsid")
            }

            override fun onUnavailable() {
                super.onUnavailable()
                if (wifiNetworkCallback !== this) return
                wifiNetworkCallback = null
                heldWifiNetwork = null
                heldWifiSsid = null
                activeWifiHoldSsid = null
                Log.d("VOICE_BG", "WiFi hold: $normalizedSsid unavailable, retrying in 2s")
                mainHandler.postDelayed({
                    if (isServiceRunning && MitraRuntime.isActive(this@BackgroundService)) {
                        holdWifiConnection(normalizedSsid, password)
                    }
                }, 2000)
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                if (heldWifiNetwork == network) {
                    heldWifiNetwork = null
                    heldWifiSsid = null
                }
                // Only retry if this is still the active callback (not replaced by a newer call)
                if (wifiNetworkCallback !== this) return
                wifiNetworkCallback = null
                activeWifiHoldSsid = null
                Log.d("VOICE_BG", "WiFi hold: lost $normalizedSsid — retrying in 2s")
                mainHandler.postDelayed({
                    if (isServiceRunning && MitraRuntime.isActive(this@BackgroundService)) {
                        holdWifiConnection(normalizedSsid, password)
                    }
                }, 2000)

            }
        }
        wifiNetworkCallback = newCallback
        activeWifiHoldSsid = normalizedSsid

        try {
            connectivityManager.requestNetwork(request, newCallback)
            Log.d("VOICE_BG", "WiFi hold: requesting $normalizedSsid")
        } catch (e: Exception) {
            Log.e("VOICE_BG", "WiFi hold failed: ${e.message}")
            wifiNetworkCallback = null
            activeWifiHoldSsid = null
        }
    }

    private fun releaseWifiHold() {
        val callback = wifiNetworkCallback
        wifiNetworkCallback = null
        heldWifiNetwork = null
        heldWifiSsid = null
        activeWifiHoldSsid = null
        callback?.let {
            try { connectivityManager.unregisterNetworkCallback(it) } catch (_: Exception) { }
        }
    }

    private fun prepareForExternalAppLaunch() {
        suppressRuntimeSpeech = true
        sendBroadcast(Intent(ACTION_BACKGROUND_MITRA_UI).setPackage(packageName))
    }

    // ===== ACTIONS =====

    private fun openApp(packageName: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) { speak("App not installed"); return }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        assistantActive = false; waitingForFollowUpCommand = false
        prepareForExternalAppLaunch()

        // Preferred path: launch via the accessibility service — this works from the background on
        // Android 12+/OEMs that block a normal service's background activity start.
        if (AutoSendAccessibilityService.tryLaunch(intent)) return

        // Fallbacks: direct start (works when recently foreground) if we have overlay permission.
        if (android.provider.Settings.canDrawOverlays(this)) {
            try { startActivity(intent) } catch (e: Exception) {
                Log.w("VOICE_BG", "openApp startActivity failed: ${e.message}")
                speak("Sorry, I could not open the app. Please enable accessibility for MITRA.")
            }
        } else {
            speak("To open apps in the background, please enable accessibility for MITRA app in settings.")
        }
    }

    private fun openAppByName(spokenName: String) {
        val query = spokenName.lowercase().replace(Regex("[^a-z0-9 ]"), "").trim()
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(mainIntent, 0)

        data class AppMatch(val packageName: String, val label: String, val score: Int)

        var best: AppMatch? = null
        for (info in apps) {
            val label = info.loadLabel(packageManager).toString()
                .lowercase().replace(Regex("[^a-z0-9 ]"), "").trim()
            val score = when {
                label == query -> 400
                label.startsWith(query) -> 300
                query.startsWith(label) -> 250
                label.contains(query) -> 200
                query.split(" ").all { token -> label.contains(token) } -> 150
                query.split(" ").any { token -> token.length > 2 && label.contains(token) } -> 80
                else -> 0
            }
            if (score > 0 && (best == null || score > best.score)) {
                best = AppMatch(info.activityInfo.packageName, label, score)
            }
        }

        if (best != null) {
            Log.d("VOICE_BG", "Opening app: ${best.label} (${best.packageName})")
            openApp(best.packageName)
        } else {
            speak("App $spokenName not found")
        }
    }

    private fun callContact(contactName: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) { speak("Call permissions are not granted"); return }

        val match = findBestContactMatch(contactName)
        if (match != null) {
            assistantActive = false
            waitingForFollowUpCommand = false
            prepareForExternalAppLaunch()
            launchIntent(Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:${match.number}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } else {
            speak("Contact not found")
        }
    }

    private fun navigateTo(destination: String) {
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("google.navigation:q=${Uri.encode(destination)}&mode=d")
        ).apply {
            setPackage("com.google.android.apps.maps")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(packageManager) != null) {
            assistantActive = false; waitingForFollowUpCommand = false
            prepareForExternalAppLaunch()
            launchIntent(intent)
        } else speak("Google Maps is not installed")
    }

    private fun sendWhatsAppMessage(contactName: String, message: String) {
        // Use the same fuzzy contact matcher as calling, so speech mis-hears like
        // "shahbaz" for "shabaz" still resolve to the right contact.
        val match = findBestContactMatch(contactName)
        if (match == null) { speak("Contact not found"); return }
        val phoneNumber = match.number.replace(" ", "").replace("+", "").replace("-", "")
        speak("Opening WhatsApp")
        prepareForExternalAppLaunch()
        launchIntent(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
            putExtra("jid", "$phoneNumber@s.whatsapp.net")
            setPackage("com.whatsapp")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun readTextFromScreen() {
        val bitmap: Bitmap? = VideoActivity.getScreenBitmap()
        if (bitmap == null) { speak("Unable to capture image"); return }
        val image = InputImage.fromBitmap(bitmap, 0)
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            .process(image)
            .addOnSuccessListener { visionText ->
                if (visionText.textBlocks.isEmpty()) speak("No text found")
                else visionText.textBlocks.forEach { speak(it.text) }
            }
            .addOnFailureListener { speak("Text recognition failed") }
    }

    private fun playOnYouTube(query: String) {
        speak("Playing $query on YouTube")
        Thread {
            val videoId = fetchTopYouTubeVideoId(query)
            mainHandler.post {
                assistantActive = false; waitingForFollowUpCommand = false
                if (videoId != null) openYouTubeVideo(videoId) else openYouTubeSearch(query)
            }
        }.start()
    }

    private fun fetchTopYouTubeVideoId(query: String): String? = try {
        fetchVideoIdFromUrl("https://www.youtube.com/results?search_query=${Uri.encode(query)}")
            ?: fetchVideoIdFromUrl(
                "https://www.google.com/search?q=${Uri.encode("$query site:youtube.com/watch")}"
            )
    } catch (e: Exception) { null }

    private fun fetchVideoIdFromUrl(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/122.0 Mobile Safari/537.36")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string().orEmpty()
            return listOf(
                Regex("\"videoId\":\"([a-zA-Z0-9_-]{11})\""),
                Regex("watch\\?v=([a-zA-Z0-9_-]{11})")
            ).firstNotNullOfOrNull { it.find(body)?.groupValues?.get(1) }
        }
    }

    // Single helper — all startActivity calls from background go through here
    private fun launchIntent(intent: Intent) {
        // Prefer the accessibility service so navigate/call/send work from the background too.
        if (AutoSendAccessibilityService.tryLaunch(intent)) return
        if (android.provider.Settings.canDrawOverlays(this)) {
            try { startActivity(intent) } catch (e: Exception) {
                Log.w("VOICE_BG", "launchIntent failed: ${e.message}")
                speak("Sorry, I could not open that. Please enable accessibility for MITRA.")
            }
        } else {
            speak("To do this in the background, please enable accessibility for MITRA app.")
        }
    }

    private fun openYouTubeVideo(videoId: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$videoId")).apply {
            setPackage("com.google.android.youtube")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(packageManager) != null) { launchIntent(intent); return }
        launchIntent(Intent(Intent.ACTION_VIEW,
            Uri.parse("https://www.youtube.com/watch?v=$videoId")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun openYouTubeSearch(query: String) {
        val intent = Intent(Intent.ACTION_SEARCH).apply {
            setPackage("com.google.android.youtube")
            putExtra("query", query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(packageManager) != null) { launchIntent(intent); return }
        launchIntent(Intent(Intent.ACTION_VIEW,
            Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    // ===== AI / WEBSOCKET =====

    /** Returns the socket factory for a network that has real internet access (e.g. cellular),
     *  bypassing the process-level MITRA_DEVICE binding which has no internet. */
    private fun internetSocketFactory(): javax.net.SocketFactory? {
        for (network in connectivityManager.allNetworks) {
            val caps = connectivityManager.getNetworkCapabilities(network) ?: continue
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                return network.socketFactory
            }
        }
        return null
    }

    private fun connectWebSocketIfNeeded() {
        if (webSocket != null) return
        val deviceId = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(MainActivity.KEY_UNIQUE_CODE, null) ?: return

        // Build a client that uses the internet network's socket factory so the WebSocket
        // goes through cellular/internet instead of the MITRA_DEVICE local network.
        val sf = internetSocketFactory()
        val client = if (sf != null) {
            OkHttpClient.Builder()
                .socketFactory(sf)
                .pingInterval(30, TimeUnit.SECONDS)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)   // no read timeout — LLM can take time
                .writeTimeout(15, TimeUnit.SECONDS)
                .build()
        } else {
            OkHttpClient.Builder()
                .pingInterval(30, TimeUnit.SECONDS)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build()
        }

        val request = Request.Builder()
            .url(getWebSocketUrl(deviceId))
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("VOICE_BG", "WebSocket connected")
                sendStreamingStatus("WebSocket connected")
                resetReconnectDelay()
            }
            // Handle text frames (JSON or plain text from server)
            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d("VOICE_BG", "WS text message: ${text.take(300)}")
                mainHandler.post {
                    if (!handleCloudTextEnvelope(text)) {
                        handleTextMessage(text)
                    }
                }
            }
            // Handle binary frames (MessagePack)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Log.d("VOICE_BG", "WS binary message: ${bytes.size} bytes")
                if (!handleCloudBinaryEnvelope(bytes.toByteArray())) {
                    handleServerMessage(bytes.toByteArray())
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("VOICE_BG", "WebSocket failure: ${t.message}")
                this@BackgroundService.webSocket = null
                sendStreamingStatus("WebSocket disconnected")
                scheduleReconnect()
                if (isAiResponding) {
                    mainHandler.post {
                        isAiResponding = false
                        speak("Sorry, I could not reach the server.")
                    }
                }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("VOICE_BG", "WebSocket closed: $code $reason")
                this@BackgroundService.webSocket = null
                sendStreamingStatus("WebSocket disconnected")
                if (code != 1000) {
                    scheduleReconnect()
                }
            }
        })
    }

    private fun sendAiQuestion(question: String) {
        connectWebSocketIfNeeded()
        // Wait up to 10 seconds for the WebSocket connection to establish
        waitForConnectionThenSend(question, attempts = 20)
    }

    private fun waitForConnectionThenSend(question: String, attempts: Int) {
        val ws = webSocket
        if (ws != null) {
            doSendAiQuestion(ws, question)
            return
        }
        if (attempts <= 0) {
            Log.e("VOICE_BG", "WebSocket not connected after 10s — giving up")
            isAiResponding = false
            speak("Could not connect to the server. Please check your internet.")
            return
        }
        // Retry every 500ms — total wait = attempts × 500ms = 10 seconds max
        Log.d("VOICE_BG", "Waiting for WebSocket… attempts left: $attempts")
        mainHandler.postDelayed({ waitForConnectionThenSend(question, attempts - 1) }, 500)
    }

    private fun doSendAiQuestion(ws: WebSocket, question: String) {
        try {
            val packer = MessagePack.newDefaultBufferPacker()
            packer.packMapHeader(1)
            packer.packString("question")
            packer.packString(question)
            packer.close()
            val sent = ws.send(packer.toByteArray().toByteString())
            Log.d("VOICE_BG", "AI question sent: '$question' success=$sent")

            // Safety timeout: if no response in 60s, stop waiting and tell user
            mainHandler.postDelayed({
                if (isAiResponding) {
                    Log.w("VOICE_BG", "No AI response after 60s — timing out")
                    isAiResponding = false
                    speak("The server is taking too long. Please try again.")
                }
            }, 60_000)
        } catch (e: Exception) {
            Log.e("VOICE_BG", "Send failed: ${e.message}")
            isAiResponding = false
            speak("Failed to send your question.")
        }
    }

    /** Handles text-frame WebSocket messages (JSON or plain text). */
    private fun handleCloudTextEnvelope(text: String): Boolean {
        val trimmed = text.trim()
        val json = try { org.json.JSONObject(trimmed) } catch (_: Exception) { return false }
        if (!json.has("response_type") && !json.has("frame_id")) return false
        lastCloudResponseAtMs = System.currentTimeMillis()
        val seq = json.optInt("seq", -1).takeIf { it >= 0 }
        val timestamp = json.optLong("timestamp", -1).takeIf { it >= 0 }
        markFrameAckReceived(seq, timestamp)
        val outerMap = jsonToMap(json)
        val innerMap = decodeNestedData(outerMap["data"]).ifEmpty { outerMap }
        logCloudFeatures(outerMap, innerMap)
        val deliverySeq = (outerMap["delivery_seq"] as? Number)?.toLong()
        noteDeliverySeq(deliverySeq)
        val stale = isStaleDelivery(deliverySeq)
        val outputText = nestedString(innerMap, "gpu3_response", "final_decision", "output_text")
            ?: (outerMap["message"] as? String)
        val action = nestedString(innerMap, "gpu3_response", "final_decision", "action")
        val frameId = outerMap["frame_id"] as? String
        val seqValue = (outerMap["seq"] as? Number)?.toInt()
        val navigation = AndroidNavigationModule.evaluate(frameId, seqValue, MobileInferenceState.latestFresh(localInferenceFreshMs), innerMap)
        CloudFrameResultStore.update(
            CloudFrameResult(
                frameId = frameId,
                responseType = outerMap["response_type"] as? String,
                outputText = outputText,
                action = action,
                outer = outerMap,
                inner = innerMap,
                navigation = navigation
            )
        )
        NavigationStateStore.update(navigation)
        if (stale) {
            Log.d("VOICE_BG", "Stale cloud response (delivery_seq=$deliverySeq) acked but not spoken")
            return true
        }
        if (deliverySeq != null) lastSpokenDeliverySeq = deliverySeq
        if (navigation != null) {
            val speechGate = NavigationStateStore.evaluateSpeech(navigation)
            if (speechGate.shouldSpeak) {
                Log.d("VOICE_BG", "Navigation TTS spoken reason=${speechGate.reason} frame_id=$frameId action=${navigation.finalAction} text=${navigation.finalText} | ${navigation.debugSummary} | src=${navigation.source}")
                aiBuffer.clear()
                if (!suppressRuntimeSpeech) {
                    mainHandler.post { speak(navigation.finalText) }
                }
            } else {
                Log.d("VOICE_BG", "Navigation TTS skipped reason=${speechGate.reason} frame_id=$frameId action=${navigation.finalAction} text=${navigation.finalText} | ${navigation.debugSummary} | src=${navigation.source}")
            }
        } else {
            Log.d("VOICE_BG", "Cloud frame response had no navigation decision; frame caption speech suppressed for MVP")
        }
        return true
    }

    private fun handleCloudBinaryEnvelope(bytes: ByteArray): Boolean {
        val outer = decodeMsgpackMap(bytes) ?: return false
        if (!outer.containsKey("response_type") && !outer.containsKey("frame_id")) return false
        lastCloudResponseAtMs = System.currentTimeMillis()
        val seq = (outer["seq"] as? Number)?.toInt()
        val timestampValue = outer["timestamp"]
        val timestampMs = when (timestampValue) {
            is Int -> timestampValue.toLong()
            is Long -> timestampValue
            is Float -> (timestampValue * 1000f).toLong()
            is Double -> (timestampValue * 1000.0).toLong()
            else -> null
        }
        markFrameAckReceived(seq, timestampMs)
        val deliverySeq = (outer["delivery_seq"] as? Number)?.toLong()
        noteDeliverySeq(deliverySeq)
        val stale = isStaleDelivery(deliverySeq)
        val inner = decodeNestedData(outer["data"]).ifEmpty { outer }
        logCloudFeatures(outer, inner)
        val outputText = nestedString(inner, "gpu3_response", "final_decision", "output_text")
            ?: (outer["message"] as? String)
        val action = nestedString(inner, "gpu3_response", "final_decision", "action")
        val frameId = outer["frame_id"] as? String
        val navigation = AndroidNavigationModule.evaluate(frameId, seq, MobileInferenceState.latestFresh(localInferenceFreshMs), inner)
        CloudFrameResultStore.update(
            CloudFrameResult(
                frameId = frameId,
                responseType = outer["response_type"] as? String,
                outputText = outputText,
                action = action,
                outer = outer,
                inner = inner,
                navigation = navigation
            )
        )
        NavigationStateStore.update(navigation)
        // Latest-frame-only: a replayed/older response is acked above but never re-spoken.
        if (stale) {
            Log.d("VOICE_BG", "Stale cloud response (delivery_seq=$deliverySeq) acked but not spoken")
            return true
        }
        if (deliverySeq != null) lastSpokenDeliverySeq = deliverySeq
        // The Navigation Module owns frame-response voice for MVP. Raw cloud captions are not spoken
        // from the navigation stream.
        if (navigation != null) {
            val speechGate = NavigationStateStore.evaluateSpeech(navigation)
            if (speechGate.shouldSpeak) {
                Log.d("VOICE_BG", "Navigation TTS spoken reason=${speechGate.reason} frame_id=$frameId action=${navigation.finalAction} text=${navigation.finalText} | ${navigation.debugSummary} | src=${navigation.source}")
                aiBuffer.clear()
                if (!suppressRuntimeSpeech) {
                    mainHandler.post { speak(navigation.finalText) }
                }
            } else {
                Log.d("VOICE_BG", "Navigation TTS skipped reason=${speechGate.reason} frame_id=$frameId action=${navigation.finalAction} text=${navigation.finalText} | ${navigation.debugSummary} | src=${navigation.source}")
            }
        } else {
            Log.d("VOICE_BG", "Cloud frame response had no navigation decision; frame caption speech suppressed for MVP")
        }
        return true
    }

    private fun handleTextMessage(text: String) {
        val trimmed = text.trim()
        val seq = try {
            org.json.JSONObject(trimmed).optInt("seq", -1).takeIf { it >= 0 }
        } catch (_: Exception) {
            null
        }
        val timestamp = try {
            org.json.JSONObject(trimmed).optLong("timestamp", -1).takeIf { it >= 0 }
        } catch (_: Exception) {
            null
        }
        markFrameAckReceived(seq, timestamp)
        try {
            val json = org.json.JSONObject(trimmed)
            // Check common server field names for the response text
            val content = json.optString("chunk")
                .ifEmpty { json.optString("answer") }
                .ifEmpty { json.optString("text") }
                .ifEmpty { json.optString("response") }
                .ifEmpty { json.optString("message") }
            val isDone = json.optBoolean("done", false) ||
                         json.optString("type") == "done" ||
                         json.optString("status") == "done"
            Log.d("VOICE_BG", "JSON parsed — content:'$content' done:$isDone")

            if (content.isNotEmpty()) {
                aiBuffer.append(content)
                // Speak when we hit a sentence boundary or buffer gets large
                val buf = aiBuffer.toString()
                if (!isDone && (buf.contains('.') || buf.contains('?') || buf.contains('!') || buf.length > 120)) {
                    aiBuffer.clear()
                    speakAiChunk(buf, isLast = false)
                }
            }
            if (isDone) {
                val remaining = aiBuffer.toString().trim()
                aiBuffer.clear()
                speakAiChunk(remaining, isLast = true)
            }
        } catch (_: Exception) {
            // Not JSON — treat as plain text answer (speak it all at once)
            Log.d("VOICE_BG", "Plain text response: ${trimmed.take(100)}")
            if (trimmed.isNotEmpty()) {
                aiBuffer.clear()
                speakAiChunk(trimmed, isLast = true)
            }
        }
    }

    private fun handleServerMessage(bytes: ByteArray) {
        val sequenceHeader = extractSequenceHeaders(bytes)
        markFrameAckReceived(sequenceHeader.first, sequenceHeader.second)
        try {
            val answer = extractAnswer(bytes)
            Log.d("VOICE_BG", "Answer extracted: '${answer.take(100)}'")
            if (answer.isNotEmpty()) {
                aiBuffer.clear()
                mainHandler.post { speakAiChunk(answer, isLast = true) }
            } else {
                mainHandler.post {
                    isAiResponding = false
                    speak("I did not receive an answer from the server.")
                }
            }
        } catch (e: Exception) {
            Log.e("VOICE_BG", "handleServerMessage error: ${e.message}")
            mainHandler.post {
                isAiResponding = false
                speak("I received an unexpected response from the server.")
            }
        }
    }

    /**
     * Parses the server's MessagePack response and returns just the answer text.
     * Server format: {status, message, device_id, data: {answer: "...", processing_time_ms}}
     */
    private fun markFrameAckReceived() {
        if (frameAckInFlight.compareAndSet(true, false)) {
            mainHandler.removeCallbacks(frameAckTimeoutRunnable)
            CloudFrameResultStore.noteAck(null)
            Log.d("VOICE_BG", "Frame ack received")
        }
    }

    private fun extractAnswer(bytes: ByteArray): String {
        val unpacker = MessagePack.newDefaultUnpacker(bytes)
        val rootType = unpacker.nextFormat.valueType.name

        // Case 1: root is a plain string — speak it directly
        if (rootType == "STRING") return unpacker.unpackString()

        // Case 2: root is a map — walk it looking for answer/data fields
        if (rootType != "MAP") return ""

        val mapSize = unpacker.unpackMapHeader()
        var directAnswer = ""
        var dataAnswer = ""

        repeat(mapSize) {
            val key = unpacker.unpackString()
            val valType = unpacker.nextFormat.valueType.name

            when {
                // Top-level answer/chunk/text/response field
                (key == "answer" || key == "chunk" || key == "text" ||
                 key == "response" || key == "content") && valType == "STRING" -> {
                    directAnswer = unpacker.unpackString()
                }
                // Nested data map — look for answer inside it
                key == "data" && valType == "MAP" -> {
                    val innerSize = unpacker.unpackMapHeader()
                    repeat(innerSize) {
                        val ik = unpacker.unpackString()
                        val ivType = unpacker.nextFormat.valueType.name
                        if ((ik == "answer" || ik == "chunk" || ik == "text" || ik == "response") && ivType == "STRING") {
                            dataAnswer = unpacker.unpackString()
                        } else {
                            unpacker.skipValue()
                        }
                    }
                }
                // Skip everything else (status, device_id, processing_time_ms, etc.)
                else -> unpacker.skipValue()
            }
        }

        return (directAnswer.ifEmpty { dataAnswer }).trim()
    }

    private fun extractSequenceHeaders(bytes: ByteArray): Pair<Int?, Long?> {
        return try {
            val unpacker = MessagePack.newDefaultUnpacker(bytes)
            if (unpacker.nextFormat.valueType.name != "MAP") return Pair(null, null)
            val mapSize = unpacker.unpackMapHeader()
            var seq: Int? = null
            var timestamp: Long? = null
            repeat(mapSize) {
                val key = unpacker.unpackString()
                when (key) {
                    "seq" -> if (unpacker.nextFormat.valueType.name == "INTEGER") seq = unpacker.unpackInt() else unpacker.skipValue()
                    "timestamp" -> if (unpacker.nextFormat.valueType.name == "INTEGER") timestamp = unpacker.unpackLong() else unpacker.skipValue()
                    else -> unpacker.skipValue()
                }
            }
            Pair(seq, timestamp)
        } catch (_: Exception) {
            Pair(null, null)
        }
    }

    private fun markFrameAckReceived(seq: Int? = null, timestamp: Long? = null) {
        synchronized(pendingFrameLock) {
            val current = pendingFrame
            if (current == null) {
                if (frameAckInFlight.compareAndSet(true, false)) {
                    mainHandler.removeCallbacks(frameAckTimeoutRunnable)
                    CloudFrameResultStore.noteAck(seq)
                    Log.d("VOICE_BG", "Frame ack received without matching pending frame")
                }
                return
            }
            if (seq != null && seq == current.seq || timestamp != null && timestamp == current.timestamp) {
                pendingFrame = null
                if (frameAckInFlight.compareAndSet(true, false)) {
                    mainHandler.removeCallbacks(frameAckTimeoutRunnable)
                    CloudFrameResultStore.noteAck(current.seq)
                    Log.d("VOICE_BG", "Frame ack received for seq=${current.seq}")
                }
            } else if (seq == null && timestamp == null) {
                pendingFrame = null
                if (frameAckInFlight.compareAndSet(true, false)) {
                    mainHandler.removeCallbacks(frameAckTimeoutRunnable)
                    CloudFrameResultStore.noteAck(current.seq)
                    Log.d("VOICE_BG", "Frame ack received (no sequence header)")
                }
            } else {
                Log.d("VOICE_BG", "Ignoring ack for seq=$seq timestamp=$timestamp; waiting on seq=${current.seq}")
            }
        }
    }

    private fun decodeNestedData(data: Any?): Map<String, Any?> {
        return when (data) {
            is Map<*, *> -> data.entries.associate { it.key.toString() to it.value }
            is ByteArray -> decodeMsgpackMap(data) ?: emptyMap()
            is okio.ByteString -> decodeMsgpackMap(data.toByteArray()) ?: emptyMap()
            is String -> try {
                jsonToMap(org.json.JSONObject(data))
            } catch (_: Exception) {
                emptyMap()
            }
            else -> emptyMap()
        }
    }

    private fun nestedString(root: Map<String, Any?>, vararg path: String): String? {
        var current: Any? = root
        for (key in path) {
            current = (current as? Map<*, *>)?.get(key) ?: return null
        }
        return current as? String
    }

    private fun jsonToMap(json: org.json.JSONObject): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        val it = json.keys()
        while (it.hasNext()) {
            val key = it.next()
            val value = json.get(key)
            out[key] = when (value) {
                is org.json.JSONObject -> jsonToMap(value)
                is org.json.JSONArray -> (0 until value.length()).map { idx ->
                    val item = value.get(idx)
                    when (item) {
                        is org.json.JSONObject -> jsonToMap(item)
                        is org.json.JSONArray -> item.toString()
                        org.json.JSONObject.NULL -> null
                        else -> item
                    }
                }
                org.json.JSONObject.NULL -> null
                else -> value
            }
        }
        return out
    }

    private fun hazardResultToFeatureResults(result: HazardFrameResult): List<Map<String, Any>> {
        val ordered = listOf(
            Feature.DAY_NIGHT,
            Feature.SCENE,
            Feature.OCR,
            Feature.FIRE_SMOKE,
            Feature.WET_DRY,
            Feature.PEDESTRIAN,
            Feature.POTHOLE,
            Feature.ELECTRIC_POLE
        )
        return ordered.mapNotNull { feature ->
            val detections = result.detectionsByFeature[feature].orEmpty()
            val top = detections.maxByOrNull { it.score }
            val executed = result.executedFeatures.contains(feature)
            val resultBody = featureResultBody(feature, result, detections, top)
            val errorMessage = if (executed) "" else (result.skippedFeatures[feature]?.name ?: "not_executed")
            mapOf(
                "feature_id" to (featureIdMap[feature] ?: -1),
                "feature_name" to (featureNameMap[feature] ?: feature.name) + if (feature == Feature.SCENE) " Detection" else "",
                "success" to executed,
                "processing_time_ms" to ((result.latenciesMs[feature] ?: 0L).toDouble()),
                "result" to resultBody,
                "detections" to detections.map { detectionToWire(detections = detections, detection = it) },
                "error_message" to errorMessage
            )
        }
    }

    private fun featureResultBody(
        feature: Feature,
        result: HazardFrameResult,
        detections: List<Detection>,
        top: Detection?
    ): Map<String, Any> {
        return when (feature) {
            Feature.DAY_NIGHT -> mapOf(
                "label" to when (result.dayNight ?: DayNight.NIGHT) {
                    DayNight.DAY -> "day"
                    DayNight.NIGHT -> "night"
                },
                "confidence" to 1.0
            )
            Feature.SCENE -> mapOf(
                "scene_type" to when (result.sceneType ?: SceneType.UNKNOWN) {
                    SceneType.INDOOR -> "indoor"
                    SceneType.OUTDOOR -> "outdoor"
                    SceneType.UNKNOWN -> "outdoor"
                },
                "scene_name" to result.sceneName(),
                "confidence" to (top?.score?.toDouble() ?: 1.0)
            )
            Feature.OCR -> mapOf(
                "label" to (top?.label ?: ""),
                "confidence" to (top?.score?.toDouble() ?: 0.0),
                "text_count" to detections.size,
                "texts" to detections.take(10).map { it.label }
            )
            else -> mapOf(
                "label" to (top?.label ?: "none"),
                "confidence" to (top?.score?.toDouble() ?: 0.0),
                "detection_count" to detections.size
            )
        }
    }

    private fun detectionToWire(detections: List<Detection>, detection: Detection): Map<String, Any> {
        val bbox = detection.boxLtrb
        val bboxMap = if (bbox != null && bbox.size >= 4) {
            mapOf(
                "x1" to bbox[0].toDouble(),
                "y1" to bbox[1].toDouble(),
                "x2" to bbox[2].toDouble(),
                "y2" to bbox[3].toDouble(),
                "format" to "xyxy",
                "normalized" to true
            )
        } else {
            emptyMap()
        }
        return mapOf(
            "class_name" to detection.label,
            "confidence" to detection.score.toDouble(),
            "bbox" to bboxMap,
            "zone" to detection.zone()
        )
    }

    private fun HazardFrameResult?.sceneName(): String {
        val label = this?.detectionsByFeature?.get(Feature.SCENE)?.firstOrNull()?.label ?: return "unknown"
        return label.substringBeforeLast("_").replace('_', ' ')
    }

    private fun SceneType?.toWireSceneType(): String = when (this ?: SceneType.UNKNOWN) {
        SceneType.INDOOR -> "indoor"
        SceneType.OUTDOOR -> "outdoor"
        SceneType.UNKNOWN -> "outdoor"
    }

    private fun DayNight?.toWireDayNight(): String = when (this ?: DayNight.DAY) {
        DayNight.DAY -> "day"
        DayNight.NIGHT -> "night"
    }

    private fun Detection.zone(): String {
        val bbox = boxLtrb ?: return "center"
        val centerX = (bbox[0] + bbox[2]) / 2f
        return when {
            centerX < 0.33f -> "left"
            centerX > 0.66f -> "right"
            else -> "center"
        }
    }

    private fun defaultSensorPayload(): Map<String, Any> {
        return mapOf(
            "gps" to mapOf(
                "lat" to 0.0,
                "lon" to 0.0,
                "alt" to 0.0,
                "acc_m" to 0.0,
                "speed_mps" to 0.0,
                "course_deg" to 0.0,
                "fix" to false
            ),
            "imu" to mapOf(
                "accel" to listOf(0.0, 0.0, 0.0),
                "gyro" to listOf(0.0, 0.0, 0.0),
                "mag" to listOf(0.0, 0.0, 0.0),
                "orientation" to mapOf(
                    "yaw" to 0.0,
                    "pitch" to 0.0,
                    "roll" to 0.0
                )
            )
        )
    }

    private fun sanitizeDeviceId(deviceId: String): String {
        val sanitized = deviceId.filter { it.isLetterOrDigit() }.take(128).ifBlank { "androiddevice01" }
        if (sanitized != deviceId) {
            Log.w("VOICE_BG", "Device id sanitized from '$deviceId' to '$sanitized' for cloud routing")
        }
        return sanitized
    }

    private fun decodeMsgpackMap(bytes: ByteArray): Map<String, Any?>? {
        return try {
            val unpacker = MessagePack.newDefaultUnpacker(bytes)
            unpackAny(unpacker) as? Map<String, Any?>
        } catch (_: Exception) {
            null
        }
    }

    private fun unpackAny(unpacker: org.msgpack.core.MessageUnpacker): Any? {
        return when (unpacker.nextFormat.valueType) {
            org.msgpack.value.ValueType.NIL -> {
                unpacker.unpackNil()
                null
            }
            org.msgpack.value.ValueType.BOOLEAN -> unpacker.unpackBoolean()
            org.msgpack.value.ValueType.INTEGER -> unpacker.unpackLong()
            org.msgpack.value.ValueType.FLOAT -> unpacker.unpackDouble()
            org.msgpack.value.ValueType.STRING -> unpacker.unpackString()
            org.msgpack.value.ValueType.BINARY -> {
                val len = unpacker.unpackBinaryHeader()
                unpacker.readPayload(len)
            }
            org.msgpack.value.ValueType.ARRAY -> {
                val size = unpacker.unpackArrayHeader()
                MutableList(size) { unpackAny(unpacker) }
            }
            org.msgpack.value.ValueType.MAP -> {
                val size = unpacker.unpackMapHeader()
                LinkedHashMap<String, Any?>(size).apply {
                    repeat(size) {
                        put(unpackAny(unpacker).toString(), unpackAny(unpacker))
                    }
                }
            }
            else -> {
                unpacker.skipValue()
                null
            }
        }
    }

    private fun speakAiChunk(text: String, isLast: Boolean) {
        if (!ttsReady) return
        val utteranceId = if (isLast) "ai_response_complete" else "ai_chunk_${System.currentTimeMillis()}"
        unmuteAudio()
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) < maxVol / 3)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol * 2 / 3, 0)
        if (text.isNotEmpty()) {
            tts.speak(text, TextToSpeech.QUEUE_ADD, Bundle().apply {
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            }, utteranceId)
        } else if (isLast) {
            // Nothing left to speak — restart listening directly
            isAiResponding = false
            isListening = false
            startListening()
        }
    }

    // ===== CONTACTS =====

    private fun findBestContactMatch(contactName: String): ContactMatch? {
        val target = normalizeContactName(contactName)
        val targetTokens = target.split(" ").filter { it.isNotBlank() }
        var best: ContactMatch? = null
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val raw = cursor.getString(cursor.getColumnIndexOrThrow(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME))
                val norm = normalizeContactName(raw)
                val number = cursor.getString(cursor.getColumnIndexOrThrow(
                    ContactsContract.CommonDataKinds.Phone.NUMBER))
                val score = scoreMatch(norm, target, targetTokens)
                if (score > 0 && (best == null || score > best!!.score))
                    best = ContactMatch(raw, norm, number, score)
            }
        }
        return best
    }

    private fun scoreMatch(name: String, target: String, tokens: List<String>): Int {
        if (target.isBlank()) return 0
        if (name == target) return 400
        val nameTokens = name.split(" ").filter { it.isNotBlank() }
        if (nameTokens.any { it == target }) return 300
        if (name.startsWith(target)) return 200
        val exactMatched = tokens.count { t -> nameTokens.any { it == t } }
        if (exactMatched == tokens.size && exactMatched > 0) return 150 + exactMatched
        if (nameTokens.any { it.startsWith(target) }) return 120
        if (name.contains(target)) return 80

        // Fuzzy phonetic matching — allow edit distance ~40% of the word length (min 2), so
        // mis-hears like "shahbaz" / "shabas" still resolve to "shabaz".
        val fuzzyMatched = tokens.count { spoken ->
            nameTokens.any { contact ->
                val maxLen = maxOf(spoken.length, contact.length)
                val allowed = maxOf(2, kotlin.math.ceil(maxLen * 0.4).toInt())
                editDistance(spoken, contact) <= allowed
            }
        }
        if (fuzzyMatched == tokens.size && fuzzyMatched > 0) return 60 + fuzzyMatched * 5
        if (fuzzyMatched > 0) return 30 + fuzzyMatched * 5

        // Whole-name compact comparison (spaces removed) as a final fuzzy fallback.
        val compactName = name.replace(" ", "")
        val compactTarget = target.replace(" ", "")
        val cMax = maxOf(compactName.length, compactTarget.length)
        if (cMax > 0 &&
            editDistance(compactTarget, compactName) <= maxOf(2, kotlin.math.ceil(cMax * 0.4).toInt())) {
            return 40
        }

        return 0
    }

    private fun normalizeContactName(name: String) =
        name.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun editDistance(a: String, b: String): Int {
        val m = a.length; val n = b.length
        val dp = Array(m + 1) { IntArray(n + 1) { 0 } }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) for (j in 1..n) {
            dp[i][j] = if (a[i-1] == b[j-1]) dp[i-1][j-1]
                       else 1 + minOf(dp[i-1][j], dp[i][j-1], dp[i-1][j-1])
        }
        return dp[m][n]
    }

    // ===== TTS =====

    private fun playAck() {
        if (ttsReady) tts.speak("Yes", TextToSpeech.QUEUE_FLUSH, null, "ack")
        mainHandler.postDelayed({ isListening = false; startListening() }, 50000)
    }

    private fun speak(text: String) {
        if (!ttsReady) return
        lastSpokenText = text
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) < maxVol / 3)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol * 2 / 3, 0)
        resumeListeningAfterSpeech = true
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }, "mitra_app_tts")
    }
}
