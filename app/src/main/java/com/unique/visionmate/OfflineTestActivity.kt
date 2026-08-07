package com.unique.visionmate

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.unique.visionmate.engine.Feature
import com.unique.visionmate.engine.HazardFrameResult
import com.unique.visionmate.engine.HazardObserver
import com.unique.visionmate.engine.TelemetryEvent
import java.util.concurrent.atomic.AtomicInteger

/**
 * Standalone test activity that runs the hazard engine on a user-picked local video.
 *
 * Usage on a phone:
 *   1. Build & install the debug APK.
 *   2. From the launcher, open "VisionMate (Engine Test)" (separate icon from "MITRA").
 *   3. Tap "Pick test video" and choose any MP4. The choice is remembered for next launch.
 *   4. Watch the on-screen status overlay; stream logs with
 *      `adb logcat -s HAZARD:V EngineBridge:V OfflineTest:V`.
 */
@androidx.media3.common.util.UnstableApi
class OfflineTestActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "OfflineTest"
        private const val PREF_VIDEO_URI = "offline.test.video.uri"
    }

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var textureView: TextureView
    private lateinit var statusView: TextView
    private lateinit var pickButton: Button

    private val processed = AtomicInteger(0)
    private val dropped = AtomicInteger(0)
    private val nightSkipped = AtomicInteger(0)
    @Volatile private var latestSummary: String = "(no frames yet)"

    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                Log.w(TAG, "could not take persistable permission: ${e.message}")
            }
            getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(PREF_VIDEO_URI, uri.toString()).apply()
            Log.i(TAG, "picked video: $uri")
            startPlaybackAndEngine(uri)
        } else {
            Log.w(TAG, "no video selected")
            statusView.text = "No video selected. Tap 'Pick test video'."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_offline_test)
        playerView = findViewById(R.id.playerView)
        textureView = playerView.videoSurfaceView as TextureView
        statusView = findViewById(R.id.statusView)
        pickButton = findViewById(R.id.pickButton)
        pickButton.setOnClickListener { pickVideo.launch(arrayOf("video/*")) }

        // Priority 1: explicit Intent extras (for `adb shell am start --es video_path ...`).
        val extraPath = intent?.getStringExtra("video_path")
        val extraUri = intent?.getStringExtra("video_uri")
        when {
            !extraPath.isNullOrBlank() -> {
                val f = java.io.File(extraPath)
                if (f.exists() && f.canRead()) {
                    Log.i(TAG, "using extra video_path: $extraPath")
                    startPlaybackAndEngine(Uri.fromFile(f))
                    return
                }
                Log.w(TAG, "extra video_path not readable: $extraPath (exists=${f.exists()})")
            }
            !extraUri.isNullOrBlank() -> {
                Log.i(TAG, "using extra video_uri: $extraUri")
                startPlaybackAndEngine(Uri.parse(extraUri))
                return
            }
        }

        // Priority 2: a video previously placed in the app's private external dir by adb push.
        val privateDir = getExternalFilesDir(null)
        if (privateDir != null) {
            val candidate = java.io.File(privateDir, "test.mp4")
            if (candidate.exists() && candidate.canRead()) {
                Log.i(TAG, "auto-using private dir video: ${candidate.absolutePath}")
                startPlaybackAndEngine(Uri.fromFile(candidate))
                return
            }
        }

        // Priority 3: previously-picked URI saved in prefs.
        val saved = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_VIDEO_URI, null)
        if (saved != null) {
            Log.i(TAG, "reusing saved video uri: $saved")
            startPlaybackAndEngine(Uri.parse(saved))
        } else {
            statusView.text = "Tap 'Pick test video' to choose a test clip,\nor adb push a video to:\n${privateDir?.absolutePath ?: "<no external dir>"}/test.mp4"
        }
    }

    private fun startPlaybackAndEngine(uri: Uri) {
        pickButton.visibility = View.GONE

        val p = ExoPlayer.Builder(this).build()
        p.setMediaItem(MediaItem.fromUri(uri))
        p.prepare()
        p.playWhenReady = true
        p.repeatMode = ExoPlayer.REPEAT_MODE_ALL
        playerView.player = p
        player = p
        Log.i(TAG, "playback started for $uri (looping)")

        val observer = object : HazardObserver {
            override fun onResult(result: HazardFrameResult) {
                processed.incrementAndGet()
                if (result.skipReason != null) {
                    if (result.skipReason == com.unique.visionmate.engine.SkipReason.NIGHT) {
                        nightSkipped.incrementAndGet()
                    }
                }
                val det = result.detectionsByFeature.mapValues { it.value.size }

                // Per-feature top detection — what each model actually saw on this frame.
                val topDetectionsLine = result.detectionsByFeature.entries
                    .filter { it.value.isNotEmpty() }
                    .joinToString("  |  ") { (f, list) ->
                        val top = list.maxByOrNull { it.score } ?: list.first()
                        val box = top.boxLtrb?.let {
                            "[${"%.2f".format(it[0])},${"%.2f".format(it[1])},${"%.2f".format(it[2])},${"%.2f".format(it[3])}]"
                        } ?: ""
                        "$f=${top.label}@${"%.2f".format(top.score)}$box(n=${list.size})"
                    }

                latestSummary =
                    "f=${result.frameId} dn=${result.dayNight} scene=${result.sceneType}\n" +
                        "ran=${result.executedFeatures}\n" +
                        "topDet: $topDetectionsLine\n" +
                        "counts=$det  latencies=${result.latenciesMs}"
                Log.i(TAG, "result: f=${result.frameId} ${topDetectionsLine}")
                runOnUiThread { updateStatus() }
            }

            override fun onTelemetry(event: TelemetryEvent) {
                when (event) {
                    is TelemetryEvent.QueueDropStale -> dropped.incrementAndGet()
                    is TelemetryEvent.FeatureDisabled -> Log.w(TAG, "feature disabled at startup: ${event.feature} reason=${event.reason}")
                    is TelemetryEvent.FeatureTimeout -> Log.w(TAG, "feature timeout: ${event.feature} on frame ${event.frameId}")
                    is TelemetryEvent.FeatureException -> Log.e(TAG, "feature exception: ${event.feature} -> ${event.message}")
                    is TelemetryEvent.FrameSkippedNight -> { /* counted via onResult */ }
                }
                runOnUiThread { updateStatus() }
            }
        }

        EngineBridge.attachAlways(this, { textureView.getBitmap() }, observer)
    }

    private fun updateStatus() {
        val s = EngineBridge.submitted.get()
        statusView.text = buildString {
            append("Submitted=$s  Processed=${processed.get()}  Dropped=${dropped.get()}  NightSkipped=${nightSkipped.get()}\n")
            append(latestSummary)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        EngineBridge.detach()
        player?.release()
        player = null
    }
}
