package com.unique.visionmate

import android.os.SystemClock

/**
 * Throttles/dedupes navigation speech (the spec's TTS rules):
 *  - Speak STOP/WAIT immediately; CAUTION/SLOW are frequent-but-rate-limited.
 *  - Speak normal WALK guidance only once every ~12 s.
 *  - Do not repeat the same final_text (cooldown per identical message).
 *  - Enforce a minimum gap between any two nav utterances so cues don't cut each other off.
 *
 * Crucially the dedup key is the action+text, NOT the frame id (frame id changes every frame, which
 * previously defeated dedup and made every frame re-speak).
 */
object NavigationStateStore {

    data class SpeechGateResult(val shouldSpeak: Boolean, val reason: String)

    private const val WALK_INTERVAL_MS = 12_000L        // normal "path clear" cadence
    private const val SAME_TEXT_COOLDOWN_MS = 10_000L   // don't repeat identical guidance
    private const val STOP_REPEAT_MS = 4_000L           // a persisting STOP may re-alert sooner
    private const val MIN_GAP_MS = 5_000L               // min gap between distinct non-urgent cues

    @Volatile private var latestDecision: NavigationDecision? = null
    @Volatile private var lastTextKey: String? = null
    @Volatile private var lastSpokenAtMs: Long = 0L
    @Volatile private var lastWalkAtMs: Long = 0L

    fun update(decision: NavigationDecision?) {
        latestDecision = decision
    }

    fun latest(): NavigationDecision? = latestDecision

    fun shouldSpeak(decision: NavigationDecision): Boolean = evaluateSpeech(decision).shouldSpeak

    fun evaluateSpeech(decision: NavigationDecision): SpeechGateResult {
        if (!decision.shouldSpeak) return SpeechGateResult(false, "decision_disabled")
        if (decision.finalText.isBlank()) return SpeechGateResult(false, "blank_text")
        val now = SystemClock.elapsedRealtime()
        val action = decision.finalAction
        val textKey = "$action|${decision.finalText.trim()}"
        val urgent = action == "STOP" || action == "WAIT"

        // Normal walking guidance: only every WALK_INTERVAL.
        if (action == "WALK") {
            val elapsed = now - lastWalkAtMs
            if (elapsed < WALK_INTERVAL_MS) {
                return SpeechGateResult(false, "walk_interval_${WALK_INTERVAL_MS - elapsed}ms")
            }
            lastTextKey = textKey; lastSpokenAtMs = now; lastWalkAtMs = now
            return SpeechGateResult(true, "walk_interval_elapsed")
        }

        // Identical message repeated: respect a cooldown (shorter for STOP).
        if (textKey == lastTextKey) {
            val cooldown = if (action == "STOP") STOP_REPEAT_MS else SAME_TEXT_COOLDOWN_MS
            val elapsed = now - lastSpokenAtMs
            if (elapsed < cooldown) {
                return SpeechGateResult(false, "same_text_cooldown_${cooldown - elapsed}ms")
            }
            lastSpokenAtMs = now
            return SpeechGateResult(true, "same_text_cooldown_elapsed")
        }

        // A new, different cue: enforce a minimum gap unless it is urgent (STOP/WAIT).
        val gap = now - lastSpokenAtMs
        if (!urgent && gap < MIN_GAP_MS) {
            return SpeechGateResult(false, "min_gap_${MIN_GAP_MS - gap}ms")
        }

        lastTextKey = textKey
        lastSpokenAtMs = now
        return SpeechGateResult(true, if (urgent) "urgent_$action" else "new_navigation_cue")
    }
}
