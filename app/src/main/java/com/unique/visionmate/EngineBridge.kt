package com.unique.visionmate

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.MainThread
import com.unique.visionmate.engine.EngineConfig
import com.unique.visionmate.engine.HazardEngine
import com.unique.visionmate.engine.HazardFrameResult
import com.unique.visionmate.engine.HazardObserver
import com.unique.visionmate.engine.TelemetryEvent
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single attachment point between :app and :engine.
 *
 * Production path: [attachIfEnabled] — gated on the `offload.enabled` SharedPreferences flag.
 * VideoActivity uses this. Per CLAUDE.md §3.5, when the flag is false the engine never starts
 * and the app behaves byte-identically to before.
 *
 * Test path: [attachAlways] — bypasses the global flag so an OfflineTestActivity/CameraActivity
 * can exercise the engine without touching production preferences.
 *
 * Threading contract: [attachIfEnabled], [attachAlways] and [detach] must be called on the main
 * thread. Engine construction (model load + GPU delegate, ~10s) happens on a background thread to
 * avoid an ANR; the resulting engine is installed back on the main thread, but ONLY if the attach
 * that started it is still the current one — see [attachGeneration].
 */
object EngineBridge {

    private const val TAG = "EngineBridge"
    private const val POLL_INTERVAL_MS = 1_000L
    private const val INITIAL_DELAY_MS = 1_500L

    private var engine: HazardEngine? = null
    private val handler = Handler(Looper.getMainLooper())
    private var pollRunnable: Runnable? = null
    @Volatile private var resultListener: ((HazardFrameResult) -> Unit)? = null

    /**
     * Monotonically increasing attach id. Every [attach] and every [detach] bumps it.
     * A background init thread captures the value at start and only installs its engine if the
     * value is unchanged when it finishes. This closes two races:
     *   H1 — activity destroyed during init: detach() bumps the id, so the finishing init discards
     *        (and stops) its engine instead of resurrecting one after the activity is gone.
     *   H2 — double attach: a second attach() bumps the id, so the first init discards its engine
     *        and only the latest attach installs a poll loop. No duplicate loops, no leaked engine.
     */
    private val attachGeneration = AtomicInteger(0)

    /** Number of frames successfully submitted since the last attach. Read by the test UI. */
    val submitted: AtomicInteger = AtomicInteger(0)

    @MainThread
    fun attachIfEnabled(activity: Activity, frameProvider: () -> Bitmap?) {
        val prefs = activity.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        if (!HazardEngine.isOffloadEnabled(prefs)) {
            Log.d(TAG, "offload.enabled=false; engine not started")
            return
        }
        attach(activity, frameProvider, defaultObserver())
    }

    @MainThread
    fun attachAlways(activity: Activity, frameProvider: () -> Bitmap?, observer: HazardObserver) {
        attach(activity, frameProvider, observer)
    }

    fun setResultListener(listener: ((HazardFrameResult) -> Unit)?) {
        resultListener = listener
    }

    @MainThread
    private fun attach(activity: Activity, frameProvider: () -> Bitmap?, observer: HazardObserver) {
        // Tear down whatever is running, then invalidate any init still in flight from before.
        detachInternal()
        val myGen = attachGeneration.incrementAndGet()

        val prefs = activity.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val appCtx = activity.applicationContext
        // Engine init loads TFLite models with GPU delegate setup. Synchronously this blocks the
        // main thread for ~10s and triggers an ANR, so do it off-thread.
        Log.i(TAG, "engine init starting on background thread (gen=$myGen)")
        Thread {
            val e = try {
                HazardEngine.create(appCtx, prefs, observer, EngineConfig.default()).also { it.start() }
            } catch (ex: Throwable) {
                Log.e(TAG, "engine init failed (gen=$myGen): ${ex.message}", ex)
                null
            }
            handler.post {
                if (e == null) return@post
                val current = attachGeneration.get()
                if (myGen != current) {
                    // A later attach() or a detach() happened while we were initializing.
                    // This engine is an orphan — stop it instead of installing it.
                    Log.w(TAG, "engine init (gen=$myGen) superseded by gen=$current; stopping orphan")
                    try { e.stop() } catch (ex: Throwable) { Log.w(TAG, "orphan stop failed: ${ex.message}") }
                    return@post
                }
                engine = e
                submitted.set(0)
                Log.i(TAG, "engine attached (gen=$myGen, frame source: ${frameProvider::class.java.simpleName})")
                startPollLoop(e, frameProvider)
            }
        }.start()
    }

    private fun startPollLoop(engine: HazardEngine, frameProvider: () -> Bitmap?) {
        var nullFrameWarned = false
        val r = object : Runnable {
            override fun run() {
                val bm = try {
                    frameProvider()
                } catch (e: Exception) {
                    Log.w(TAG, "frame provider threw: ${e.message}")
                    null
                }
                if (bm != null) {
                    nullFrameWarned = false
                    engine.submitFrame(bm)
                    val n = submitted.incrementAndGet()
                    if (n == 1 || n % 10 == 0) Log.i(TAG, "submitted $n frames")
                } else if (!nullFrameWarned) {
                    Log.w(TAG, "frame provider returned null (texture not ready or playback paused)")
                    nullFrameWarned = true
                }
                handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
        pollRunnable = r
        handler.postDelayed(r, INITIAL_DELAY_MS)
    }

    private fun defaultObserver() = object : HazardObserver {
        override fun onResult(result: HazardFrameResult) {
            MobileInferenceState.update(result)
            Log.i(
                TAG,
                "frame=${result.frameId} dn=${result.dayNight} scene=${result.sceneType} " +
                    "executed=${result.executedFeatures} skipped=${result.skippedFeatures.keys}"
            )
            resultListener?.invoke(result)
        }
        override fun onTelemetry(event: TelemetryEvent) {
            Log.w(TAG, "telemetry: $event")
        }
    }

    /**
     * Public detach. Bumps [attachGeneration] first so any init still in flight is invalidated
     * and will stop its own engine instead of installing it, THEN tears down the running engine.
     */
    @MainThread
    fun detach() {
        attachGeneration.incrementAndGet()
        detachInternal()
    }

    /** Tears down the current poll loop + engine. Does not touch the generation counter. */
    @MainThread
    private fun detachInternal() {
        pollRunnable?.let { handler.removeCallbacks(it) }
        pollRunnable = null
        engine?.let {
            try { it.stop() } catch (ex: Throwable) { Log.w(TAG, "engine stop failed: ${ex.message}") }
        }
        engine = null
        Log.i(TAG, "engine detached")
    }
}
