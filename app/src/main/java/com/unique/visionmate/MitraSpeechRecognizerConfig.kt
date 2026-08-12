package com.unique.visionmate

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.speech.ModelDownloadListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Shared recognizer setup for fixed MITRA commands.
 *
 * MITRA hardware WiFi normally has no internet route, so command recognition must prefer
 * Android's on-device/offline path whenever the phone exposes it.
 */
internal object MitraSpeechRecognizerConfig {
    const val MODE_ON_DEVICE = "on-device"
    const val MODE_DEFAULT = "default-offline-preferred"
    const val MODE_DEFAULT_ONLINE_ALLOWED = "default-online-allowed"
    const val COMMAND_LANGUAGE_TAG = "en-US"
    const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
    const val ERROR_LANGUAGE_UNAVAILABLE = 13

    data class Created(
        val recognizer: SpeechRecognizer,
        val mode: String,
        val languageTag: String = COMMAND_LANGUAGE_TAG,
        val preferOffline: Boolean = true
    )

    fun create(
        context: Context,
        forceDefault: Boolean = false,
        allowOnlineFallback: Boolean = false,
        logTag: String
    ): Created {
        val canUseOnDevice = !forceDefault && isOnDeviceAvailable(context)
        if (canUseOnDevice) {
            return try {
                Created(
                    recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context),
                    mode = MODE_ON_DEVICE
                )
            } catch (e: UnsupportedOperationException) {
                Log.w(logTag, "on-device recognizer became unavailable; falling back", e)
                Created(
                    recognizer = SpeechRecognizer.createSpeechRecognizer(context),
                    mode = MODE_DEFAULT
                )
            }
        }

        return Created(
            recognizer = SpeechRecognizer.createSpeechRecognizer(context),
            mode = if (allowOnlineFallback) MODE_DEFAULT_ONLINE_ALLOWED else MODE_DEFAULT,
            preferOffline = !allowOnlineFallback
        )
    }

    fun commandIntent(
        maxResults: Int,
        preferOffline: Boolean = true,
        possiblyCompleteSilenceMs: Long? = null,
        completeSilenceMs: Long? = null
    ): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, COMMAND_LANGUAGE_TAG)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, maxResults)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
        possiblyCompleteSilenceMs?.let {
            @Suppress("DEPRECATION")
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, it)
        }
        completeSilenceMs?.let {
            @Suppress("DEPRECATION")
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, it)
        }
    }

    fun shouldFallbackFromOnDevice(error: Int): Boolean {
        return error == SpeechRecognizer.ERROR_CLIENT ||
            error == SpeechRecognizer.ERROR_SERVER ||
            error == SpeechRecognizer.ERROR_NETWORK ||
            error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
            error == ERROR_LANGUAGE_NOT_SUPPORTED ||
            error == ERROR_LANGUAGE_UNAVAILABLE
    }

    fun hasValidatedInternet(context: Context): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun requestOfflineModelDownload(
        context: Context,
        recognizer: SpeechRecognizer,
        intent: Intent,
        logTag: String,
        source: String,
        onSuccess: () -> Unit
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Log.w(logTag, "offline speech model download unsupported on API ${Build.VERSION.SDK_INT}")
            return false
        }
        if (!hasValidatedInternet(context)) {
            Log.w(
                logTag,
                "offline speech model missing; no validated internet for model download source=$source"
            )
            return false
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                recognizer.triggerModelDownload(
                    intent,
                    context.mainExecutor,
                    object : ModelDownloadListener {
                        override fun onSuccess() {
                            Log.i(logTag, "offline speech model ready source=$source")
                            onSuccess()
                        }

                        override fun onProgress(completedPercent: Int) {
                            Log.i(logTag, "offline speech model download=$completedPercent% source=$source")
                        }

                        override fun onScheduled() {
                            Log.i(logTag, "offline speech model download scheduled source=$source")
                        }

                        override fun onError(error: Int) {
                            Log.w(logTag, "offline speech model download failed error=$error source=$source")
                        }
                    }
                )
            } else {
                recognizer.triggerModelDownload(intent)
                Log.i(logTag, "offline speech model download requested source=$source")
            }
            true
        } catch (e: Exception) {
            Log.w(logTag, "offline speech model download request failed source=$source: ${e.message}", e)
            false
        }
    }

    private fun isOnDeviceAvailable(context: Context): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            try {
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            } catch (_: Exception) {
                false
            }
    }
}
