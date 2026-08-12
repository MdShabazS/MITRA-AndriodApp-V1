# MITRA Poco Mic And Adaptive RTSP Refresh Report - 2026-08-12

Owner: MdShabazS / Codex

Scope:

- Review user-reported Poco issues after the RTSP live-refresh fix.
- Confirm why the mic was turning on/off repeatedly.
- Reduce RTSP refresh interruption/stuck-feed behavior.
- Keep cloud testing pending.

## Issues Reported

1. Mic sound toggled on/off repeatedly, even when not streaming.
2. Voice commands such as `mitra`, `start mitra`, and spoken WiFi password were not usable.
3. Hardware feed delay was about 2-3 seconds and sometimes stuck.
4. RTSP refresh took too long.

## Findings

### Mic / Command Failure

Poco logcat showed Android SpeechRecognizer errors from the Google recognizer path:

```text
STT error code: 12
LANGUAGE_PACK_ERROR
```

and later:

```text
STT error code: 2
GRPC_ERROR
```

The previous app behavior retried quickly after those errors. On Poco/HyperOS, each retry enabled and disabled the physical mic path, causing the audible mic on/off sound.

Two app problems were fixed:

- The app no longer forces offline SpeechRecognizer mode. Poco did not have the required offline language pack.
- SpeechRecognizer hard errors now use increasing backoff instead of a tight retry loop.
- `ACTION_PAUSE_LISTENING` now pauses the service recognizer even while MITRA runtime is active, so setup/password listening can own the mic.

### RTSP Refresh / Stuck Feed

The previous patched build fixed the repeated 800 ms live-refresh reconnect storm, but still had a timer-based 5-minute refresh. That refresh can interrupt a healthy stream and feel slow.

The new behavior is adaptive:

- Android still watches for stale frames.
- Android no longer refreshes a healthy stream just because 5 minutes passed.
- A live-refresh is scheduled only when the session is older than 5 minutes and the sampled frame age is already stale enough to justify recovery.

## Verification

### Main-Screen Mic Stability

Folder:

```text
docs/test-evidence/2026-08-12-poco-mic-refresh-fix/
```

Method:

- Installed patched APK on Poco.
- Cleared logcat.
- Launched MITRA main screen.
- Waited 60 seconds without starting the stream.

Counts from `main_screen_mic_counts.txt`:

| Metric | Count |
|---|---:|
| STT/setup recognizer errors | 2 |
| Speech retry backoff logs | 2 |
| Mic enable events | 2 |
| Mic disable events | 2 |

Interpretation:

- The rapid mic on/off loop was not reproduced.
- The remaining recognizer errors were backed off instead of retried continuously.
- While connected to a no-internet route, speech recognition can still fail depending on installed Google speech/offline packs; the app now avoids the audible retry loop.

### Adaptive RTSP Refresh

Method:

- Started the hardware RTSP stream on Poco.
- Confirmed initial stream was live: about `2.2` sampled FPS, latest frame about `112ms`.
- Ran a 6.5-minute window crossing the old 5-minute forced-refresh point.

Final screenshot overlay:

```text
MITRA hardware: live
Sampled: 572  Sample FPS: 2.2  640x480
Last frame: 226ms ago
```

Counts from `adaptive_refresh_counts.txt`:

| Metric | Count |
|---|---:|
| `scheduling reconnect (live-refresh)` | 0 |
| `refreshing stale live RTSP session` | 0 |
| old forced refresh log | 0 |
| stall reconnect | 0 |
| PixelCopy failure | 0 |

Interpretation:

- The app did not interrupt a healthy stream at the old 5-minute point.
- The stream stayed live through 6.5 minutes with fresh sampled frames.
- Exact camera-to-phone latency still needs hardware timestamp/frame-counter proof.

## Current Decision

This still does not justify resetting or recoding the hardware from scratch.

The app-side fixes should be retested in a longer full run:

- 15-minute Poco stream run on this build.
- Human voice command test while on normal internet and while connected to MITRA WiFi.
- Spoken WiFi password test if saved credentials are cleared.
- Background wake-word test after pressing Home.

Cloud remains pending.
