# MITRA Android App

MITRA is an Android assistive-vision client for `com.unique.visionmate`.

## Branch Status

This branch contains the tested OPPO-working backup version from `BACKUPS/oppo_working_2026-08-03`. It is the version selected for GitHub upload because it was tested with the MITRA hardware stream on OPPO before the later local experiments.

## Current App Flow

- On start, the app checks required permissions and setup.
- It searches for the MITRA hardware WiFi (`MITRA_DEVICE`).
- If the phone is already connected to MITRA WiFi, it skips re-scanning and starts the stream path.
- Hardware video is read from `rtsp://10.42.0.1:8554/stream`.
- RTSP uses the tested hardware stream path and fallback behavior from the OPPO-working backup.
- RTSP playback uses low LibVLC cache settings and a 5-minute live-session refresh to reduce long-run buffer buildup.
- If hardware is unavailable, the app falls back to the phone back camera.
- Frames are sampled for local inference and cloud upload; the live RTSP display can still decode around 30 FPS.
- Local TFLite models run hazard features; navigation guidance is integrated in the Android app flow.
- TTS speaks guidance with throttling, cached-detection expiry, and fresh-frame confirmation for local hazards to reduce repeated or stale alerts.

## Why Rahil's Hardware Stream Work Matters

The Android app can reduce buffering and ignore stale AI results, but it cannot fully fix a hardware stream that sends delayed, corrupted, or incorrectly timestamped frames. Rahil's low-latency firmware/RTSP work is expected to solve the root stream problems:

- Stop live-feed delay from growing over time, such as the observed 2-3 second delay becoming about 8 seconds.
- Reduce decoder/render stutter on Android by sending correctly timed, low-latency frames.
- Remove repeated blocky/color stream corruption.
- Prevent old buffered camera frames from being treated as current AI input.
- Improve local hazard accuracy for person, wet, pothole, fire/smoke, and indoor/outdoor scene decisions.
- Make reconnect reliable without requiring a hardware reboot.
- Add timestamp/frame-counter proof so the team can measure real camera-to-phone latency.

Product target: the hardware must provide a live navigation camera feed, not only an RTSP stream that opens. Rahil should follow `docs/HARDWARE_FIRMWARE_REBUILD_SPEC.md` for the exact WiFi/RTSP contract, encoder settings, timestamp overlay, 10-minute tests, definition of done, and fail conditions.

## Main Modules

- `app`: Android UI, permissions, WiFi pairing, RTSP display, voice service, accessibility service, cloud streaming, and app integration.
- `engine`: Local on-device hazard inference pipeline and feature runners.
- `app/src/main/assets/models`: Active bundled TFLite models and model manifest.

## Team Documentation

This project is maintained by a team. Every meaningful change must update Markdown documentation so app, AI, and hardware engineers can understand the work without guessing.

- `docs/MITRA_PROJECT_MASTER_HANDOFF.md`: start-here architecture, completed work, pending work, QA, and handoff guide for future teammates or AI chats.
- `docs/DOCUMENTATION_STANDARDS.md`: required documentation process for future work.
- `docs/WORK_LOG.md`: chronological record of completed tasks.
- `docs/HARDWARE_INTEGRATION.md`: hardware-facing WiFi, RTSP, camera, and AI pipeline expectations.
- `docs/HARDWARE_FIRMWARE_REBUILD_SPEC.md`: Rahil's firmware reset/rebuild contract if the current hardware stream stack cannot be tuned.
- `docs/RAHIL_CODEX_START_GUIDE.md`: exact prompt and step-by-step Codex startup guide for Rahil.
- `docs/ANDROID_DEVICE_COMPATIBILITY_PLAN.md`: release gate and test matrix for running MITRA across supported Android phones.
- `WORKFLOW.md`: end-to-end app behavior.
- `COMMANDS.md`: voice command behavior.
- `STREAMING_METADATA_SCHEMA.md`: streaming metadata contract.

When code changes, update the relevant docs in the same branch.

## Build

Use Android Studio or:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process :app:assembleDebug
```

Debug APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## GitHub Notes

This repository should include source, Gradle wrapper files, resources, tests, and active app model assets.

Do not commit generated/local files such as:

- `.gradle/`, `.kotlin/`, `build/`, `app/build/`, `engine/build/`
- `.idea/`, `local.properties`
- `LOGS/`, proof screenshots, old model backups, share APKs/zips

The active TFLite files are intentionally kept because the app depends on them at runtime.
