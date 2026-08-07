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
- If hardware is unavailable, the app falls back to the phone back camera.
- Frames are sampled for local inference and cloud upload; the live RTSP display can still decode around 30 FPS.
- Local TFLite models run hazard features; navigation guidance is integrated in the Android app flow.
- TTS speaks guidance with throttling to avoid repeated voice spam.

## Main Modules

- `app`: Android UI, permissions, WiFi pairing, RTSP display, voice service, accessibility service, cloud streaming, and app integration.
- `engine`: Local on-device hazard inference pipeline and feature runners.
- `app/src/main/assets/models`: Active bundled TFLite models and model manifest.

## Team Documentation

This project is maintained by a team. Every meaningful change must update Markdown documentation so app, AI, and hardware engineers can understand the work without guessing.

- `docs/DOCUMENTATION_STANDARDS.md`: required documentation process for future work.
- `docs/WORK_LOG.md`: chronological record of completed tasks.
- `docs/HARDWARE_INTEGRATION.md`: hardware-facing WiFi, RTSP, camera, and AI pipeline expectations.
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
