package com.unique.visionmate.engine

import android.util.Log

internal object Telemetry {
    private const val TAG = "HAZARD"
    fun d(msg: String) { safeLog { Log.d(TAG, msg) } }
    fun i(msg: String) { safeLog { Log.i(TAG, msg) } }
    fun w(msg: String) { safeLog { Log.w(TAG, msg) } }
    fun e(msg: String, t: Throwable? = null) { safeLog { Log.e(TAG, msg, t) } }

    private inline fun safeLog(block: () -> Unit) {
        try {
            block()
        } catch (_: RuntimeException) {
            // JVM unit tests use Android stub classes where android.util.Log is not implemented.
        }
    }
}
