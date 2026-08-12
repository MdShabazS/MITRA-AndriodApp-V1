package com.unique.visionmate

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.location.LocationManager
import android.util.Log
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "visionmate_prefs"
        const val KEY_SSID = "saved_ssid"
        const val KEY_PASSWORD = "saved_password"
        const val KEY_UNIQUE_CODE = "unique_code"
        const val KEY_WAKE_WORD = "wake_word"
        const val KEY_MOBILE_NUMBER = "mobile_number"
        const val KEY_STREAMING_ENDPOINT = "streaming_endpoint"
        const val KEY_STREAMING_FPS = "streaming_fps"
        const val KEY_ACCESSIBILITY_PROMPTED = "accessibility_prompted"
        // Launch action: brings the app to the foreground and begins the MITRA search.
        const val ACTION_START_SEARCH = "com.unique.visionmate.START_SEARCH"
        const val TARGET_SSID = "MITRA_DEVICE"
        private const val PERMISSION_REQUEST_CODE = 101
        private const val BATTERY_OPT_REQUEST_CODE = 200
        private const val ACCESSIBILITY_REQUEST_CODE = 201
        private const val OVERLAY_REQUEST_CODE = 202
        private const val SETTINGS_RETURN_RECHECK_MS = 1_200L
        private const val WIFI_SCAN_RESULTS_DELAY_MS = 1_200L
        private const val WIFI_SCAN_RETRY_DELAY_MS = 750L
        private const val WIFI_CONNECT_TIMEOUT_MS = 10_000
        private const val STT_LANGUAGE_PACK_ERROR_CODE =
            MitraSpeechRecognizerConfig.ERROR_LANGUAGE_NOT_SUPPORTED
        private const val STT_LANGUAGE_UNAVAILABLE_ERROR_CODE =
            MitraSpeechRecognizerConfig.ERROR_LANGUAGE_UNAVAILABLE
        private const val STT_MAX_BACKOFF_MS = 60_000L
    }

    // TTS
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var pendingAction: (() -> Unit)? = null

    // Speech recognizer
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var speechIntent: Intent
    private var speechRecognizerMode = MitraSpeechRecognizerConfig.MODE_DEFAULT
    private var forceDefaultSpeechRecognizer = false
    private var allowOnlineSpeechFallback = false
    private var lastSpeechModelDownloadRequestAtMs = 0L
    private var isListening = false
    private var sttHardErrorCount = 0
    private var sttBackoffUntilMs = 0L
    private var sttRetryGeneration = 0

    // Managers
    private lateinit var wifiManager: WifiManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var audioManager: AudioManager
    private lateinit var preferences: SharedPreferences
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // UI controls
    private lateinit var statusView: TextView
    private lateinit var connectionStatusView: TextView
    private lateinit var endpointInput: EditText
    private lateinit var fpsInput: EditText
    private lateinit var startStreamingButton: Button
    private lateinit var stopStreamingButton: Button
    private lateinit var voiceButton: android.widget.ImageButton
    private var voiceOn = true
    private var pendingStartSearch = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action == BackgroundService.ACTION_STREAMING_STATUS) {
                val message = intent.getStringExtra(BackgroundService.EXTRA_STATUS_MESSAGE)
                    ?: getString(R.string.connection_status_ready)
                connectionStatusView.text = message
            } else if (intent?.action == BackgroundService.ACTION_BACKGROUND_MITRA_UI) {
                moveTaskToBack(true)
            } else if (intent?.action == BackgroundService.ACTION_VOICE_START_SEARCH) {
                // User said "start mitra" — begin the search (startConnectionFlow turns voice off).
                startConnectionFlow()
            }
        }
    }

    // Flow state
    private var connectionFlowActive = false
    private var pendingSettingsReturnCode = 0
    private var waitingForPassword = false
    private var waitingForWakeWordChoice = false
    private var waitingForNewWakeWord = false
    private var waitingForMobileNumber = false
    private var waitingForYes = false
    private var scanRetryCount = 0
    private var silentReconnect = false
    private var openingVideoScreen = false
    private var selectedMitraSsid: String? = null
    private var triedSavedWifiAfterScanMiss = false
    private val maxWifiScanAttempts = 4

    // Repeating voice guide while user is in a settings screen
    private val mainHandler = Handler(Looper.getMainLooper())
    private var settingsGuideRunnable: Runnable? = null

    // ===== LIFECYCLE =====

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        preferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        ensureRuntimeDefaults()
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        Log.i("MITRA_BUILD", "version=${packageInfo.versionName} code=$versionCode package=$packageName")

        statusView = findViewById(R.id.statusView)
        connectionStatusView = findViewById(R.id.connectionStatusView)
        endpointInput = findViewById(R.id.endpointInput)
        fpsInput = findViewById(R.id.fpsInput)
        startStreamingButton = findViewById(R.id.startStreamingButton)
        stopStreamingButton = findViewById(R.id.stopStreamingButton)

        endpointInput.setText(preferences.getString(KEY_STREAMING_ENDPOINT, getString(R.string.streaming_endpoint)))
        fpsInput.setText("1")

        startStreamingButton.setOnClickListener {
            applyEndpoint()
            applyFps()
            // Only now begin searching for the MITRA hardware / Wi-Fi (or fall back to camera).
            startConnectionFlow()
        }
        stopStreamingButton.setOnClickListener {
            stopMitraRuntime()
        }

        // Round mic button: ON/OFF toggle for voice control.
        //   ON  -> highlighted (blue) mic, listening for commands.
        //   OFF -> greyed with a cross, microphone paused.
        voiceButton = findViewById(R.id.voiceButton)
        updateVoiceButton()
        voiceButton.setOnClickListener {
            if (!voiceOn) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                    voiceOn = true
                    updateVoiceButton()
                    startForegroundService(Intent(this, BackgroundService::class.java))
                    sendToService(BackgroundService.ACTION_ACTIVATE_VOICE)
                    statusView.text = "Voice control ON. Say a command like 'open WhatsApp'."
                    speak("Voice control on.")
                } else {
                    ActivityCompat.requestPermissions(
                        this, arrayOf(Manifest.permission.RECORD_AUDIO), 1001
                    )
                }
            } else {
                voiceOn = false
                updateVoiceButton()
                sendToService(BackgroundService.ACTION_PAUSE_LISTENING)
                statusView.text = "Voice control OFF."
                speak("Voice control off.")
            }
        }

        // Enable WiFi silently if off
        if (!wifiManager.isWifiEnabled) {
            @Suppress("DEPRECATION")
            wifiManager.isWifiEnabled = true
        }

        // Only start the background service if RECORD_AUDIO is already granted.
        // On first launch it won't be — we start it after the user grants the permission.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            startForegroundService(Intent(this, BackgroundService::class.java))
        }

        // Kick off the MITRA Wi-Fi hold as early as possible (already-paired users).
        // The hold uses WifiNetworkSpecifier+requestNetwork, which keeps the phone associated to the
        // no-internet hotspot (otherwise the OS deauths it and falls back to cellular). Cloud upload
        // still works because BackgroundService pins the WebSocket to the cellular network, so mobile
        // data must remain ON — the two networks are used at once.
        startWifiHoldIfPaired()

        setupSpeechRecognizer()

        tts = TextToSpeech(this) { status ->
            // TTS callback may run on a background thread — post to main thread
            runOnUiThread {
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true
                    tts.language = Locale.US
                    tts.setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            runOnUiThread {
                                val action = pendingAction
                                pendingAction = null
                                action?.invoke()
                                // Resume listening now that we've finished speaking (if still on).
                                resumeVoiceAfterSpeech()
                            }
                        }
                        override fun onError(utteranceId: String?) {
                            runOnUiThread {
                                val action = pendingAction
                                pendingAction = null
                                action?.invoke()
                                resumeVoiceAfterSpeech()
                            }
                        }
                    })
                    // Do NOT auto-start the search. Just greet and wait for the user to tap Start
                    // (or the microphone button). This lets the user teach voice commands without
                    // the camera/Wi-Fi search kicking in.
                    if (!MitraRuntime.isActive(this)) {
                        speak("Voice enabled. Say start mitra to begin, or a command like open an app.")
                    }
                }
            }
        }
    }

    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        setIntent(newIntent)
        if (newIntent.action == ACTION_START_SEARCH) pendingStartSearch = true
    }

    override fun onResume() {
        super.onResume()
        if (openingVideoScreen) {
            openingVideoScreen = false
            return
        }
        ContextCompat.registerReceiver(
            this,
            statusReceiver,
            IntentFilter().apply {
                addAction(BackgroundService.ACTION_STREAMING_STATUS)
                addAction(BackgroundService.ACTION_BACKGROUND_MITRA_UI)
                addAction(BackgroundService.ACTION_VOICE_START_SEARCH)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        stopSettingsGuide()   // user came back — stop repeating instructions

        if (connectionFlowActive && pendingSettingsReturnCode != 0) {
            val requestCode = pendingSettingsReturnCode
            pendingSettingsReturnCode = 0
            mainHandler.postDelayed({ handleSettingsReturn(requestCode) }, SETTINGS_RETURN_RECHECK_MS)
            return
        }

        // Launched by "start mitra" (from background/home screen) — begin the search now.
        if (pendingStartSearch || intent?.action == ACTION_START_SEARCH) {
            pendingStartSearch = false
            intent?.action = null
            startConnectionFlow()
            return
        }

        if (MitraRuntime.isActive(this)) {
            stopListening()
            connectionFlowActive = false
            connectionStatusView.text = "MITRA app active"
            sendToService(BackgroundService.ACTION_START_MITRA_RUNTIME)
            // Still honor voice control on the home page so direct commands work here too.
            if (voiceOn && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
                sendToService(BackgroundService.ACTION_ACTIVATE_VOICE)
            }
            return
        }
        // If voice control is on, let the service listen for commands and the "start mitra" phrase;
        // otherwise pause it so it doesn't compete for the microphone.
        if (voiceOn && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            // Voice on: activate direct-command listening so "open WhatsApp" works from the home page.
            startForegroundService(Intent(this, BackgroundService::class.java))
            sendToService(BackgroundService.ACTION_ACTIVATE_VOICE)
        } else {
            sendToService(BackgroundService.ACTION_PAUSE_LISTENING)
        }

        // Reset so flow can restart (e.g., user came back from VideoActivity or settings)
        connectionFlowActive = false
        waitingForPassword = false
        waitingForWakeWordChoice = false
        waitingForNewWakeWord = false
        waitingForMobileNumber = false
        waitingForYes = false
        stopListening()

        // Do not auto-start the connection/search flow on resume. The user starts it explicitly
        // by tapping the Start button. This keeps the app idle (no camera/Wi-Fi search) until asked.
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        stopListening()
        if (openingVideoScreen) return
        if (MitraRuntime.isActive(this)) {
            sendToService(BackgroundService.ACTION_START_MITRA_RUNTIME)
            return
        }
        // Going to background: if voice is on, keep the service listening (wake-word mode) so voice
        // commands still work from anywhere; otherwise stop listening.
        if (voiceOn) {
            sendToService(BackgroundService.ACTION_RESUME_LISTENING)
        } else {
            sendToService(BackgroundService.ACTION_PAUSE_LISTENING)
        }
    }

    private fun notifyServiceWakeWordChanged(wakeWord: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            startService(Intent(this, BackgroundService::class.java).apply {
                action = BackgroundService.ACTION_UPDATE_WAKE_WORD
                putExtra("wake_word", "mitra")
            })
        }
    }

    private fun sendToService(action: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            startService(Intent(this, BackgroundService::class.java).apply {
                this.action = action
            })
        }
    }

    private fun applyEndpoint() {
        val endpoint = endpointInput.text.toString().trim().takeIf { it.isNotBlank() }
            ?: getString(R.string.streaming_endpoint)
        preferences.edit().putString(KEY_STREAMING_ENDPOINT, endpoint).apply()
        statusView.text = "Endpoint set to: $endpoint"
    }

    private fun ensureRuntimeDefaults() {
        val currentEndpoint = preferences.getString(KEY_STREAMING_ENDPOINT, null)
        // Known-stale endpoints from earlier servers — migrate any of them to the current default.
        val staleEndpoints = setOf(
            "ws://216.48.182.37:8766/ws",
            "ws://164.52.209.93:8765/ws"
        )
        val newEndpoint = getString(R.string.streaming_endpoint)

        val editor = preferences.edit()
        var changed = false

        if (currentEndpoint.isNullOrBlank() || currentEndpoint in staleEndpoints) {
            editor.putString(KEY_STREAMING_ENDPOINT, newEndpoint)
            changed = true
        }

        val currentFps = preferences.getInt(KEY_STREAMING_FPS, 0)
        if (!preferences.contains(KEY_STREAMING_FPS) || currentFps == 2 || currentFps <= 0) {
            editor.putInt(KEY_STREAMING_FPS, 1)
            changed = true
        }

        val wakeWord = preferences.getString(KEY_WAKE_WORD, null)
        if (wakeWord != "mitra") {
            editor.putString(KEY_WAKE_WORD, "mitra")
            changed = true
        }

        if (preferences.getBoolean("offload.enabled", false) != true) {
            editor.putBoolean("offload.enabled", true)
            changed = true
        }

        if (changed) editor.apply()
    }

    private fun applyFps() {
        val fps = 1
        preferences.edit().putInt(KEY_STREAMING_FPS, fps).apply()
        fpsInput.setText(fps.toString())
        statusView.text = "Frame rate set to $fps fps"
    }

    private fun startStreaming() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            speakThen("Allow camera.") {
                requestPermissionsThenScan(silent = false)
            }
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            startForegroundService(Intent(this, BackgroundService::class.java))
        }

        sendToService(BackgroundService.ACTION_START_MITRA_RUNTIME)
        sendToService(BackgroundService.ACTION_START_STREAMING)
        statusView.text = "MITRA app started"
    }

    private fun stopMitraRuntime() {
        MitraRuntime.setActive(this, false)
        sendToService(BackgroundService.ACTION_STOP_MITRA_RUNTIME)
        sendToService(BackgroundService.ACTION_STOP_STREAMING)
        statusView.text = "MITRA app stopped"
        connectionStatusView.text = "MITRA app connection: disconnected"
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSettingsGuide()
        networkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
        if (::speechRecognizer.isInitialized) speechRecognizer.destroy()
        if (::tts.isInitialized) tts.shutdown()
    }

    // ===== CONNECTION FLOW =====

    private fun startConnectionFlow() {
        if (connectionFlowActive) return
        connectionFlowActive = true
        scanRetryCount = 0
        selectedMitraSsid = null
        triedSavedWifiAfterScanMiss = false

        // Once MITRA starts searching, turn voice control off (the setup flow needs the mic).
        voiceOn = false
        if (::voiceButton.isInitialized) updateVoiceButton()
        sendToService(BackgroundService.ACTION_PAUSE_LISTENING)

        silentReconnect = !preferences.getString(KEY_PASSWORD, null).isNullOrBlank()
        requestBatteryOptimization()
    }

    private fun requestBatteryOptimization() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            requestAccessibilityService()
            return
        }
        speakThen("Allow battery.") {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            pendingSettingsReturnCode = BATTERY_OPT_REQUEST_CODE
            @Suppress("DEPRECATION")
            startActivityForResult(intent, BATTERY_OPT_REQUEST_CODE)
        }
    }

    private fun requestAccessibilityService() {
        if (isAccessibilityServiceEnabled()) {
            requestOverlayPermission()
            return
        }
        val guide = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            "Enable MITRA accessibility."
        } else {
            "Enable MITRA accessibility."
        }

        speakThen(guide) {
            startSettingsGuide(guide)
            // Try to open this service's dedicated accessibility page. On Android 14+/16 that
            // intent (ACCESSIBILITY_DETAILS_SETTINGS) can require a system permission the app does
            // not hold and throws SecurityException — so fall back to the general Accessibility
            // settings list, and never let this crash the app.
            val detailsIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").apply {
                    putExtra(
                        Intent.EXTRA_COMPONENT_NAME,
                        ComponentName(packageName, AutoSendAccessibilityService::class.java.name)
                            .flattenToString()
                    )
                }
            } else null

            @Suppress("DEPRECATION")
            try {
                pendingSettingsReturnCode = ACCESSIBILITY_REQUEST_CODE
                if (detailsIntent != null) {
                    startActivityForResult(detailsIntent, ACCESSIBILITY_REQUEST_CODE)
                } else {
                    startActivityForResult(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), ACCESSIBILITY_REQUEST_CODE)
                }
            } catch (e: Exception) {
                Log.w("MainActivity", "Accessibility details settings not available (${e.message}); opening general list")
                try {
                    pendingSettingsReturnCode = ACCESSIBILITY_REQUEST_CODE
                    startActivityForResult(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), ACCESSIBILITY_REQUEST_CODE)
                } catch (e2: Exception) {
                    Log.e("MainActivity", "Could not open accessibility settings: ${e2.message}", e2)
                    pendingSettingsReturnCode = 0
                    requestOverlayPermission()
                }
            }
        }
    }


    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            requestPermissionsThenScan(silent = false)
            return
        }

        val guide = "Enable display over apps."

        speakThen(guide) {
            startSettingsGuide(guide)
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            pendingSettingsReturnCode = OVERLAY_REQUEST_CODE
            @Suppress("DEPRECATION")
            startActivityForResult(intent, OVERLAY_REQUEST_CODE)
        }
    }

    /** Repeats the instruction while the user is in a settings screen. */
    private fun startSettingsGuide(message: String) {
        stopSettingsGuide()
        val r = object : Runnable {
            override fun run() {
                if (ttsReady) tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "guide")
                mainHandler.postDelayed(this, 12_000)
            }
        }
        settingsGuideRunnable = r
        mainHandler.postDelayed(r, 12_000)
    }

    private fun stopSettingsGuide() {
        settingsGuideRunnable?.let { mainHandler.removeCallbacks(it) }
        settingsGuideRunnable = null
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedService = "$packageName/${AutoSendAccessibilityService::class.java.canonicalName}"
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(":").any { it.equals(expectedService, ignoreCase = true) }
    }

    @Deprecated("Using for result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == BATTERY_OPT_REQUEST_CODE ||
            requestCode == ACCESSIBILITY_REQUEST_CODE ||
            requestCode == OVERLAY_REQUEST_CODE) {
            pendingSettingsReturnCode = 0
            handleSettingsReturn(requestCode)
            return
        }
    }

    private fun handleSettingsReturn(requestCode: Int) {
        when (requestCode) {
            BATTERY_OPT_REQUEST_CODE -> requestAccessibilityService()

            ACCESSIBILITY_REQUEST_CODE -> {
                if (isAccessibilityServiceEnabled()) {
                    speakThen("Accessibility enabled.") {
                        requestOverlayPermission()
                    }
                } else {
                    mainHandler.postDelayed({
                        if (isAccessibilityServiceEnabled()) {
                            speakThen("Accessibility enabled.") {
                                requestOverlayPermission()
                            }
                        } else {
                            // Asked once already — don't reopen settings again. Continue with setup.
                            speakThen("Accessibility is not enabled. Background controls will be limited.") {
                                requestOverlayPermission()
                            }
                        }
                    }, SETTINGS_RETURN_RECHECK_MS)
                }
            }

            OVERLAY_REQUEST_CODE -> {
                mainHandler.postDelayed({
                    if (Settings.canDrawOverlays(this)) {
                        speakThen("Display enabled.") {
                            requestPermissionsThenScan(silent = false)
                        }
                    } else {
                        speakThen(
                            "Display over apps is not enabled. Continuing setup."
                        ) {
                            requestPermissionsThenScan(silent = false)
                        }
                    }
                }, SETTINGS_RETURN_RECHECK_MS)
            }
        }
    }

    private fun requestPermissionsThenScan(silent: Boolean) {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
            != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.CAMERA)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.RECORD_AUDIO)

        if (needed.isNotEmpty()) {
            speakThen(
                "Allow permissions."
            ) {
                ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSION_REQUEST_CODE)
            }
        } else {
            if (silent) {
                startWifiScan()
            } else {
                speakThen("Searching MITRA.") { startWifiScan() }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            // RECORD_AUDIO is now granted — safe to start the microphone foreground service
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
                startForegroundService(Intent(this, BackgroundService::class.java))
            }
            startWifiScan()
        }
    }

    // ===== WIFI =====

    @SuppressLint("MissingPermission")
    private fun startWifiScan() {
        scanRetryCount++
        if (!isLocationEnabled()) {
            // Android hides WiFi scan results unless Location is enabled.
            statusView.text = "Turn on Location to search MITRA WiFi."
            connectionFlowActive = false
            pendingStartSearch = true
            speakThen("Turn on Location.") {
                try {
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                } catch (_: Exception) { }
            }
            return
        }

        if (!wifiManager.isWifiEnabled) {
            statusView.text = "Turn on WiFi to search MITRA."
            connectionFlowActive = false
            pendingStartSearch = true
            speakThen("Turn on WiFi.") {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startActivity(Intent(Settings.Panel.ACTION_WIFI))
                    } else {
                        @Suppress("DEPRECATION")
                        wifiManager.isWifiEnabled = true
                    }
                } catch (_: Exception) { }
            }
            return
        }

        currentConnectedSsid()?.takeIf { isMitraSsid(it) }?.let { ssid ->
            onAlreadyConnectedToMitra(ssid)
            return
        }

        try {
            val started = wifiManager.startScan()
            statusView.text = if (started) {
                "Scanning for MITRA WiFi ($scanRetryCount/$maxWifiScanAttempts)"
            } else {
                "Using cached WiFi results ($scanRetryCount/$maxWifiScanAttempts)"
            }
        } catch (e: Exception) {
            Log.w("MITRA_WIFI", "WiFi scan start failed: ${e.message}")
        }

        Handler(Looper.getMainLooper()).postDelayed({
            checkScanResults()
        }, WIFI_SCAN_RESULTS_DELAY_MS)
    }

    @SuppressLint("MissingPermission")
    private fun checkScanResults() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            // Without location we can't scan for the MITRA Wi-Fi — use the phone camera.
            speakThen("Using the phone camera.") { startCameraFallback() }
            return
        }

        val results: List<ScanResult> = try {
            wifiManager.scanResults ?: emptyList()
        } catch (_: Exception) { emptyList() }

        val nearbySsids = results
            .mapNotNull { normalizeSsid(it.SSID).takeIf(String::isNotBlank) }
            .plus(currentConnectedSsid().orEmpty())
            .filter { it.isNotBlank() }
            .distinct()
        val currentSsid = currentConnectedSsid()
        if (isMitraSsid(currentSsid)) {
            onAlreadyConnectedToMitra(currentSsid!!)
            return
        }

        val mitraSsid = nearbySsids.firstOrNull { it.equals(TARGET_SSID, ignoreCase = true) }
            ?: nearbySsids.firstOrNull { it.contains("MITRA", ignoreCase = true) }
        Log.d("MITRA_WIFI", "scan_attempt=$scanRetryCount nearby=${nearbySsids.joinToString()} selected=$mitraSsid")

        statusView.text = if (mitraSsid != null) {
            "Found $mitraSsid"
        } else {
            "Searching for MITRA WiFi ($scanRetryCount/$maxWifiScanAttempts). Nearby: ${nearbySsids.take(5).joinToString()}"
        }

        if (mitraSsid != null) {
            // MITRA hardware Wi-Fi is in range — proceed to ask for the password and connect (RTSP).
            selectedMitraSsid = mitraSsid
            onMitraDeviceFound(mitraSsid)
        } else {
            retryWifiScan()
        }
    }

    private fun retryWifiScan() {
        if (scanRetryCount >= maxWifiScanAttempts) {
            val savedSsid = preferences.getString(KEY_SSID, null)
            val savedPassword = preferences.getString(KEY_PASSWORD, null)
            if (!triedSavedWifiAfterScanMiss &&
                !savedPassword.isNullOrBlank() &&
                (savedSsid.isNullOrBlank() || isMitraSsid(savedSsid))) {
                triedSavedWifiAfterScanMiss = true
                val ssid = savedSsid?.takeIf { it.isNotBlank() } ?: TARGET_SSID
                Log.i("MITRA_WIFI", "scan missed MITRA; trying saved WiFi credentials for $ssid before fallback")
                statusView.text = "Trying saved MITRA WiFi"
                connectToWifi(ssid, savedPassword)
                return
            }
                    speakThen("Using phone camera.") {
                connectionFlowActive = false
                startCameraFallback()
            }
            return
        }
        Handler(Looper.getMainLooper()).postDelayed({ startWifiScan() }, WIFI_SCAN_RETRY_DELAY_MS)
    }

    private fun onMitraDeviceFound(ssid: String) {
        if (isMitraSsid(currentConnectedSsid())) {
            onAlreadyConnectedToMitra(ssid)
            return
        }

        val savedSsid = preferences.getString(KEY_SSID, null)
        val savedPassword = preferences.getString(KEY_PASSWORD, null)
        val passwordToUse = savedPassword?.takeIf {
            it.isNotBlank() && (savedSsid.isNullOrBlank() || savedSsid.equals(ssid, ignoreCase = true))
        }

        if (passwordToUse != null) {
            if (silentReconnect) {
                connectToWifi(ssid, passwordToUse)   // no announcement
            } else {
                speakThen("MITRA found. Connecting.") {
                    connectToWifi(ssid, passwordToUse)
                }
            }
        } else {
            speakThen("Say WiFi password.") {
                waitingForPassword = true
                startListening()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToWifi(ssid: String, password: String) {
        statusView.text = "Trying to connect to $ssid"
        networkCallback?.let {
            try { connectivityManager.unregisterNetworkCallback(it) } catch (_: Exception) { }
        }
        rememberWifiSuggestion(ssid, password)

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(password)
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                try { connectivityManager.bindProcessToNetwork(network) } catch (_: Exception) { }
                runOnUiThread { statusView.text = "Connected to $ssid" }
                runOnUiThread { onWifiConnected(ssid, password) }
            }

            override fun onUnavailable() {
                super.onUnavailable()
                runOnUiThread {
                    currentConnectedSsid()?.takeIf { isMitraSsid(it) }?.let {
                        Log.i("MITRA_WIFI", "requestNetwork unavailable but phone is already on $it; proceeding")
                        onAlreadyConnectedToMitra(it)
                        return@runOnUiThread
                    }
                    statusView.text = "Failed to connect to $ssid"
                    if (triedSavedWifiAfterScanMiss) {
                        speakThen("Using phone camera.") {
                            connectionFlowActive = false
                            startCameraFallback()
                        }
                    } else {
                        speakThen("Connection failed. Say password again.") {
                            waitingForPassword = true
                            startListening()
                        }
                    }
                }
            }
        }

        try {
            Log.i("MITRA_WIFI", "requesting network for $ssid")
            connectivityManager.requestNetwork(request, networkCallback!!, WIFI_CONNECT_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.e("MITRA_WIFI", "requestNetwork failed for $ssid: ${e.message}", e)
            speak("Network error. Please try again.")
        }
    }

    private fun rememberWifiSuggestion(ssid: String, password: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val suggestion = WifiNetworkSuggestion.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(password)
                .build()
            val status = wifiManager.addNetworkSuggestions(listOf(suggestion))
            Log.i("MITRA_WIFI", "saved WiFi suggestion for $ssid status=$status")
        } catch (e: Exception) {
            Log.w("MITRA_WIFI", "WiFi suggestion failed for $ssid: ${e.message}")
        }
    }

    private fun normalizeSsid(ssid: String?): String {
        return ssid?.trim()?.trim('"') ?: ""
    }

    private fun currentConnectedSsid(): String? {
        return try {
            normalizeSsid(wifiManager.connectionInfo?.ssid)
        } catch (_: Exception) {
            null
        }
    }

    private fun isMitraSsid(ssid: String?): Boolean {
        val normalized = normalizeSsid(ssid)
        return normalized.equals(TARGET_SSID, ignoreCase = true) ||
            normalized.contains("MITRA", ignoreCase = true)
    }

    private fun onAlreadyConnectedToMitra(ssid: String) {
        Log.i("MITRA_WIFI", "already connected to $ssid; skipping scan/connect request")
        connectionFlowActive = false
        selectedMitraSsid = ssid
        statusView.text = "Connected to $ssid"
        preferences.edit()
            .putString(KEY_SSID, ssid)
            .putString(KEY_WAKE_WORD, "mitra")
            .apply()
        notifyServiceWakeWordChanged("mitra")
        ensureAudibleVolume()
        getOrCreateUniqueCode()
        if (silentReconnect) {
            startVideoRtsp()
        } else {
            speakThen("Opening live view.") { startVideoRtsp() }
        }
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        return locationManager?.isLocationEnabled == true
    }

    /** If the device was already paired, start holding the MITRA Wi-Fi immediately at app entry. */
    private fun startWifiHoldIfPaired() {
        val ssid = preferences.getString(KEY_SSID, null)
        val password = preferences.getString(KEY_PASSWORD, null)
        if (ssid.isNullOrBlank() || password.isNullOrBlank()) return
        val holdIntent = Intent(this, BackgroundService::class.java).apply {
            action = BackgroundService.ACTION_HOLD_WIFI
            putExtra("ssid", ssid)
            putExtra("password", password)
        }
        try { startService(holdIntent) } catch (_: Exception) { }
    }

    private fun onWifiConnected(ssid: String, password: String) {
        // Save credentials for auto-reconnect
        preferences.edit()
            .putString(KEY_SSID, ssid)
            .putString(KEY_PASSWORD, password)
            .apply()

        // Hand the WiFi hold off to BackgroundService so it survives Activity destruction
        val holdIntent = Intent(this, BackgroundService::class.java).apply {
            action = BackgroundService.ACTION_HOLD_WIFI
            putExtra("ssid", ssid)
            putExtra("password", password)
        }
        startService(holdIntent)

        ensureAudibleVolume()

        preferences.edit().putString(KEY_WAKE_WORD, "mitra").apply()
        notifyServiceWakeWordChanged("mitra")
        getOrCreateUniqueCode()
        announceCodeAndStream()
    }

    private fun checkMobileNumber() {
        val saved = preferences.getString(KEY_MOBILE_NUMBER, null)
        if (saved.isNullOrBlank()) {
            askMobileNumber()
        } else {
            announceCodeAndStream()
        }
    }

    private fun askMobileNumber() {
        speakThen("Please say your 10 digit mobile number.") {
            waitingForMobileNumber = true
            startListening()
        }
    }

    /** Extracts digits from spoken text, handling both numeric and word forms. */
    private fun extractDigits(text: String): String {
        val wordMap = mapOf(
            "zero" to "0", "one" to "1", "two" to "2", "three" to "3", "four" to "4",
            "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9"
        )
        var processed = text.lowercase()
        for ((word, digit) in wordMap) processed = processed.replace(word, digit)
        return processed.filter { it.isDigit() }
    }

    private fun announceCodeAndStream() {
        val wakeWord = "MITRA"

        val message = if (silentReconnect)
            "Starting live stream now."
        else
            "Your wake word is $wakeWord. Starting live stream now."

        speakThen(message) {
            waitingForYes = false
            launchVideoActivity()
        }
    }

    private fun getOrCreateUniqueCode(): String {
        var code = preferences.getString(KEY_UNIQUE_CODE, null)
        if (code == null) {
            // 4-digit permanent code — easy to say and remember
            code = (1000..9999).random().toString()
            preferences.edit().putString(KEY_UNIQUE_CODE, code).apply()
        }
        return code
    }

    // ===== SPEECH RECOGNITION =====

    private fun setupSpeechRecognizer() {
        try { speechRecognizer.destroy() } catch (_: Exception) {}

        val created = MitraSpeechRecognizerConfig.create(
            this,
            forceDefaultSpeechRecognizer,
            allowOnlineSpeechFallback,
            "MITRA_SETUP_STT"
        )
        speechRecognizer = created.recognizer
        speechRecognizerMode = created.mode
        speechIntent = MitraSpeechRecognizerConfig.commandIntent(
            maxResults = 3,
            preferOffline = created.preferOffline
        )
        Log.i(
            "MITRA_SETUP_STT",
            "setup recognizer mode=$speechRecognizerMode locale=${created.languageTag} preferOffline=${created.preferOffline}"
        )
        if (speechRecognizerMode != MitraSpeechRecognizerConfig.MODE_ON_DEVICE) {
            maybeRequestOfflineSpeechModelDownload("setup-internet-prepare")
        }

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                isListening = false
                resetSpeechErrorBackoff()
                val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.getOrNull(0)?.lowercase()?.trim() ?: ""
                handleVoiceInput(text)
            }

            override fun onError(error: Int) {
                isListening = false
                if (
                    speechRecognizerMode == MitraSpeechRecognizerConfig.MODE_ON_DEVICE &&
                    MitraSpeechRecognizerConfig.shouldFallbackFromOnDevice(error)
                ) {
                    forceDefaultSpeechRecognizer = true
                    Log.w(
                        "MITRA_SETUP_STT",
                        "on-device setup recognizer failed error=$error; falling back to default offline-preferred recognizer"
                    )
                }
                if (handleSetupOfflineSpeechModelUnavailable(error)) {
                    return
                }
                if (waitingForPassword || waitingForYes) {
                    scheduleSetupSpeechRetry(error)
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

    private fun handleSetupOfflineSpeechModelUnavailable(error: Int): Boolean {
        if (error != STT_LANGUAGE_UNAVAILABLE_ERROR_CODE) return false

        maybeRequestOfflineSpeechModelDownload("setup-error-$error")

        if (!allowOnlineSpeechFallback && MitraSpeechRecognizerConfig.hasValidatedInternet(this)) {
            allowOnlineSpeechFallback = true
            forceDefaultSpeechRecognizer = true
            resetSpeechErrorBackoff()
            statusView.text = "Preparing voice commands with internet fallback."
            Log.w(
                "MITRA_SETUP_STT",
                "offline setup speech model unavailable; validated internet found, temporarily allowing online recognition"
            )
            scheduleSetupListeningRetry(800L, recreate = true)
            return true
        }

        statusView.text = "Offline voice model missing. Connect this phone to internet once, then reopen MITRA."
        Log.w(
            "MITRA_SETUP_STT",
            "offline setup speech model unavailable and no internet fallback"
        )
        return false
    }

    private fun maybeRequestOfflineSpeechModelDownload(source: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastSpeechModelDownloadRequestAtMs < 30 * 60 * 1000L) return
        val requested = MitraSpeechRecognizerConfig.requestOfflineModelDownload(
            context = this,
            recognizer = speechRecognizer,
            intent = MitraSpeechRecognizerConfig.commandIntent(maxResults = 3, preferOffline = true),
            logTag = "MITRA_SETUP_STT",
            source = source
        ) {
            allowOnlineSpeechFallback = false
            forceDefaultSpeechRecognizer = false
            resetSpeechErrorBackoff()
            setupSpeechRecognizer()
            statusView.text = "Offline voice commands are ready."
        }
        if (requested) lastSpeechModelDownloadRequestAtMs = now
    }

    private fun handleVoiceInput(text: String) {
        when {
            waitingForMobileNumber -> {
                waitingForMobileNumber = false
                val digits = extractDigits(text)
                if (digits.length >= 5) {
                    val lastFive = digits.takeLast(5)
                    preferences.edit()
                        .putString(KEY_MOBILE_NUMBER, digits)
                        .putString(KEY_UNIQUE_CODE, lastFive)
                        .apply()
                    speakThen("Got it. Starting live stream now.") { announceCodeAndStream() }
                } else {
                    speakThen("I could not catch that. Please say your 10 digit mobile number again.") {
                        waitingForMobileNumber = true
                        startListening()
                    }
                }
            }

            waitingForWakeWordChoice -> {
                waitingForWakeWordChoice = false
                preferences.edit().putString(KEY_WAKE_WORD, "mitra").apply()
                notifyServiceWakeWordChanged("mitra")
                speakThen("MITRA is your wake word.") { checkMobileNumber() }
            }

            waitingForNewWakeWord -> {
                waitingForNewWakeWord = false
                preferences.edit().putString(KEY_WAKE_WORD, "mitra").apply()
                notifyServiceWakeWordChanged("mitra")
                speakThen("MITRA is your wake word.") { checkMobileNumber() }
            }

            waitingForPassword -> {
                waitingForPassword = false
                val password = normalizeSpokenWifiPassword(text)
                if (password.isNotEmpty()) {
                    speakThen("Connecting.") {
                        connectToWifi(selectedMitraSsid ?: TARGET_SSID, password)
                    }
                } else {
                    speakThen("I did not catch that. Please say the password again.") {
                        waitingForPassword = true
                        startListening()
                    }
                }
            }

            waitingForYes -> {
                if (text.contains("yes") || text.contains("yeah") ||
                    text.contains("ok") || text.contains("start") || text.contains("go")
                ) {
                    waitingForYes = false
                    launchVideoActivity()
                } else {
                    speakThen("Say yes to start streaming.") {
                        waitingForYes = true
                        startListening()
                    }
                }
            }
        }
    }

    private fun normalizeSpokenWifiPassword(raw: String): String {
        val tokenMap = mapOf(
            "zero" to "0", "oh" to "0", "o" to "0",
            "one" to "1", "won" to "1",
            "two" to "2", "to" to "2", "too" to "2",
            "three" to "3", "tree" to "3",
            "four" to "4", "for" to "4",
            "five" to "5",
            "six" to "6",
            "seven" to "7",
            "eight" to "8", "ate" to "8",
            "nine" to "9",
            "at" to "@",
            "dot" to ".",
            "dash" to "-",
            "hyphen" to "-",
            "underscore" to "_",
            "hash" to "#",
            "hashtag" to "#",
            "star" to "*",
            "asterisk" to "*"
        )
        return raw.lowercase(Locale.US)
            .replace(Regex("[,;:]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString("") { token ->
                tokenMap[token] ?: token.filter { it.isLetterOrDigit() || it in "@._#*-" }
            }
    }

    private fun startListening() {
        if (isListening) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now < sttBackoffUntilMs) {
            scheduleSetupListeningRetry(sttBackoffUntilMs - now)
            return
        }
        isListening = true
        Handler(Looper.getMainLooper()).postDelayed({
            if (isListening) {
                try {
                    speechRecognizer.startListening(speechIntent)
                } catch (e: Exception) {
                    isListening = false
                    Log.w("MITRA_SETUP_STT", "setup recognizer start failed: ${e.message}")
                    if (waitingForPassword || waitingForYes) scheduleSetupSpeechRetry(STT_LANGUAGE_PACK_ERROR_CODE)
                }
            }
        }, 300)
    }

    private fun stopListening() {
        sttRetryGeneration++
        if (!isListening) return
        try { speechRecognizer.stopListening() } catch (_: Exception) { }
        isListening = false
    }

    private fun resetSpeechErrorBackoff() {
        sttHardErrorCount = 0
        sttBackoffUntilMs = 0L
    }

    private fun scheduleSetupSpeechRetry(error: Int) {
        val isHardError = error == STT_LANGUAGE_PACK_ERROR_CODE ||
            error == STT_LANGUAGE_UNAVAILABLE_ERROR_CODE ||
            error == SpeechRecognizer.ERROR_CLIENT ||
            error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
            error == SpeechRecognizer.ERROR_NETWORK ||
            error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
            error == SpeechRecognizer.ERROR_SERVER
        sttHardErrorCount = if (isHardError) (sttHardErrorCount + 1).coerceAtMost(8) else 0
        val delayMs = if (isHardError) {
            when (sttHardErrorCount) {
                1 -> 1_500L
                2 -> 5_000L
                3 -> 15_000L
                4 -> 30_000L
                else -> STT_MAX_BACKOFF_MS
            }
        } else {
            1_500L
        }
        sttBackoffUntilMs = android.os.SystemClock.elapsedRealtime() + delayMs
        Log.w(
            "MITRA_SETUP_STT",
            "setup recognizer error=$error; retrying in ${delayMs}ms (hardErrors=$sttHardErrorCount)"
        )
        scheduleSetupListeningRetry(delayMs, recreate = isHardError)
    }

    private fun scheduleSetupListeningRetry(delayMs: Long, recreate: Boolean = false) {
        val generation = ++sttRetryGeneration
        Handler(Looper.getMainLooper()).postDelayed({
            if (generation != sttRetryGeneration || !(waitingForPassword || waitingForYes)) return@postDelayed
            if (recreate) setupSpeechRecognizer()
            startListening()
        }, delayMs)
    }

    private fun launchVideoActivity() {
        startVideoRtsp()
    }

    private fun routeToBestSource() {
        pendingAction = null
        stopListening()
        if (ttsReady) {
            try { tts.stop() } catch (_: Exception) { }
        }
        statusView.text = "Checking for MITRA device..."
        speak("Checking MITRA.")
        Thread {
            val hardwarePresent = isMitraHardwareReachable()
            Handler(Looper.getMainLooper()).post {
                if (isFinishing || isDestroyed) return@post
                if (hardwarePresent) {
                    Log.i("MITRA_PROBE", "MITRA hardware reachable -> RTSP video path")
                    statusView.text = "MITRA device found. Starting live view."
                    startVideoRtsp()
                } else {
                    Log.i("MITRA_PROBE", "MITRA hardware not reachable -> phone camera fallback")
                    statusView.text = "MITRA device not found. Using phone camera."
                    speak("Using phone camera.")
                    startCameraFallback()
                }
            }
        }.start()
    }

    /**
     * True if the MITRA hardware's RTSP server (10.42.0.1:8554) accepts a TCP connection within the
     * timeout. If the app already holds the MITRA Wi-Fi (via BackgroundService) we probe over that
     * network; otherwise a plain socket is used and will simply fail when the device is absent.
     */
    private fun isMitraHardwareReachable(): Boolean {
        val held = try { BackgroundService.currentHeldWifiNetwork() } catch (_: Exception) { null }
        return try {
            val socket = held?.socketFactory?.createSocket() ?: java.net.Socket()
            socket.use {
                it.connect(java.net.InetSocketAddress("10.42.0.1", 8554), 2500)
                true
            }
        } catch (e: Exception) {
            Log.d("MITRA_PROBE", "MITRA hardware not reachable: ${e.message}")
            false
        }
    }

    /** Live RTSP path — used when the MITRA hardware is present. */
    private fun startVideoRtsp() {
        if (openingVideoScreen) {
            Log.d("MITRA_PROBE", "VideoActivity launch already in progress; ignoring duplicate request")
            return
        }
        openingVideoScreen = true
        connectionFlowActive = false
        MitraRuntime.setActive(this, true)
        statusView.text = "Opening live MITRA feed"
        sendToService(BackgroundService.ACTION_START_MITRA_RUNTIME)
        startActivity(Intent(this, VideoActivity::class.java))
        sendToService(BackgroundService.ACTION_START_STREAMING)
    }

    /** Phone back-camera fallback — used when no MITRA hardware is reachable. */
    private fun startCameraFallback() {
        if (openingVideoScreen) {
            Log.d("MITRA_PROBE", "CameraActivity launch already in progress; ignoring duplicate request")
            return
        }
        openingVideoScreen = true
        connectionFlowActive = false
        MitraRuntime.setActive(this, true)
        sendToService(BackgroundService.ACTION_START_MITRA_RUNTIME)
        startActivity(Intent(this, CameraActivity::class.java))
        sendToService(BackgroundService.ACTION_START_STREAMING)
    }

    /** Reflects the voice on/off state on the round mic button (mic icon always; highlight = on). */
    private fun updateVoiceButton() {
        voiceButton.setImageResource(android.R.drawable.ic_btn_speak_now)
        voiceButton.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (voiceOn) 0xFF1565C0.toInt() else 0xFF3A3A3A.toInt()
        )
        voiceButton.alpha = if (voiceOn) 1.0f else 0.45f
        voiceButton.contentDescription =
            if (voiceOn) "Voice control is on. Double tap to turn it off."
            else "Voice control is off. Double tap to turn it on."
    }

    // ===== TTS HELPERS =====

    private fun speak(text: String) {
        if (!ttsReady) return
        ensureAudibleVolume()
        // Pause the service recognizer while we speak so it doesn't hear (and act on) our own voice.
        pauseVoiceForSpeech()
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "setup_tts")
    }

    /** Stops the service from listening while the app is speaking (prevents TTS self-echo). */
    private fun pauseVoiceForSpeech() {
        sendToService(BackgroundService.ACTION_PAUSE_LISTENING)
    }

    /** Re-activates direct-command listening after the app finishes speaking, if voice is on. */
    private fun resumeVoiceAfterSpeech() {
        if (voiceOn && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            sendToService(BackgroundService.ACTION_ACTIVATE_VOICE)
        }
    }

    private fun speakThen(text: String, action: () -> Unit) {
        if (!ttsReady) {
            Handler(Looper.getMainLooper()).postDelayed({ speakThen(text, action) }, 300)
            return
        }
        ensureAudibleVolume()
        pauseVoiceForSpeech()
        pendingAction = action
        val params = Bundle()
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "setup_tts")
    }

    private fun ensureAudibleVolume() {
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) < maxVol / 3) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol * 2 / 3, 0)
        }
    }
}
