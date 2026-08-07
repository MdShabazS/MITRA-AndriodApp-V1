package com.unique.visionmate.engine

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class HazardEngine private constructor(
    private val observer: HazardObserver,
    private val config: EngineConfig,
    private val pipeline: Pipeline,
    private val mailbox: Mailbox<Frame>
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val frameIds = AtomicLong(0)
    private val stopped = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private var workerJob: Job? = null

    fun start() {
        if (workerJob?.isActive == true) return
        Telemetry.i("engine starting (staleAgeMs=${config.staleAgeMs} gpuTimeout=${config.gpuTimeoutMs} ocrTimeout=${config.ocrTimeoutMs} luminanceThreshold=${config.luminanceThreshold})")
        workerJob = scope.launch { runLoop() }
    }

    fun submitFrame(bitmap: Bitmap, tsMs: Long = SystemClock.elapsedRealtime()): Boolean {
        if (stopped.get()) {
            try {
                if (!bitmap.isRecycled) bitmap.recycle()
            } catch (_: Exception) {
            }
            return false
        }
        val frame = Frame(bitmap, frameIds.incrementAndGet(), tsMs)
        val displaced = mailbox.offer(frame)
        if (displaced != null) {
            val ageMs = SystemClock.elapsedRealtime() - displaced.tsMs
            recycleFrameBitmap(displaced)
            observer.onTelemetry(TelemetryEvent.QueueDropStale(displaced.frameId, ageMs))
        }
        return true
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        Telemetry.i("engine stopping")
        val job = workerJob
        workerJob = null
        mailbox.close()?.let(::recycleFrameBitmap)
        if (job == null) {
            closePipelineAndScope()
            return
        }
        job.invokeOnCompletion { closePipelineAndScope() }
        job.cancel()
    }

    private fun closePipelineAndScope() {
        if (!closed.compareAndSet(false, true)) return
        try {
            pipeline.close()
        } catch (e: Exception) {
            Telemetry.e("pipeline close failed", e)
        } finally {
            scope.cancel()
            Telemetry.i("engine stopped")
        }
    }

    private suspend fun runLoop() {
        while (scope.isActive) {
            val frame = mailbox.take() ?: return
            val now = SystemClock.elapsedRealtime()
            val age = now - frame.tsMs
            if (age > config.staleAgeMs) {
                recycleFrameBitmap(frame)
                observer.onTelemetry(TelemetryEvent.QueueDropStale(frame.frameId, age))
                continue
            }
            try {
                val result = pipeline.process(frame)
                observer.onResult(result)
            } catch (_: CancellationException) {
                Telemetry.i("pipeline cancelled on frame ${frame.frameId}")
            } catch (e: Exception) {
                Telemetry.e("pipeline error on frame ${frame.frameId}", e)
            } finally {
                recycleFrameBitmap(frame)
            }
        }
    }

    private fun recycleFrameBitmap(frame: Frame) {
        try {
            if (!frame.bitmap.isRecycled) {
                frame.bitmap.recycle()
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        const val PREF_OFFLOAD_ENABLED: String = "offload.enabled"
        const val PREF_DEBUG_DISABLE_NIGHT_GATE: String = "offload.debug.disableNightGate"

        fun isOffloadEnabled(prefs: SharedPreferences): Boolean =
            prefs.getBoolean(PREF_OFFLOAD_ENABLED, false)

        fun create(
            context: Context,
            prefs: SharedPreferences,
            observer: HazardObserver,
            config: EngineConfig = EngineConfig.default()
        ): HazardEngine {
            val app = context.applicationContext
            val flags = FeatureFlags(prefs)
            val effectiveConfig = config.copy(
                debugDisableNightGate = prefs.getBoolean(PREF_DEBUG_DISABLE_NIGHT_GATE, false)
            )
            val pipeline = Pipeline(app, effectiveConfig, flags, observer)
            return HazardEngine(observer, effectiveConfig, pipeline, Mailbox())
        }
    }
}
