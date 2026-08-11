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

## Rahil Hardware Stream Work

Latest decision after the 2026-08-11 15-minute Android retest: do not reset or recode the hardware from scratch first. The earlier 8-second app delay did not reproduce in that later retest, and an Android RTSP reconnect-loop bug was found and fixed after the test.

Rahil's current priority is to document and preserve the working hardware implementation, then tune only what new evidence proves is needed:

- Fill `docs/HARDWARE_AS_BUILT_CAPTURE_TEMPLATE.md` with board, camera, encoder, RTSP server, stream format, and startup details.
- Back up and upload the current hardware source code, startup scripts, configs, and dependency/version list.
- Add timestamp/frame-counter proof so the team can measure real camera-to-phone latency.
- Confirm codec/profile/resolution/FPS/bitrate/GOP/B-frame/SPS/PPS and RTSP transport behavior.
- Verify Mac and Android 15-minute stream tests after the Android reconnect-loop fix is installed.
- Tune hardware only if delay growth, corruption, reconnect failure, or timestamp problems are reproduced.

Product target: the hardware must provide a documented, reproducible, live navigation camera feed, not only an RTSP stream that opens. Rahil should start with `docs/RAHIL_CODEX_START_GUIDE.md` and use `docs/HARDWARE_FIRMWARE_REBUILD_SPEC.md` only if targeted tuning cannot meet the product gates.

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
- `docs/HARDWARE_AS_BUILT_CAPTURE_TEMPLATE.md`: template Rahil should fill with real hardware/code/stream details.
- `docs/HARDWARE_FIRMWARE_REBUILD_SPEC.md`: Rahil's firmware tuning and fallback reset/rebuild contract if the current hardware stream stack cannot meet product gates.
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
