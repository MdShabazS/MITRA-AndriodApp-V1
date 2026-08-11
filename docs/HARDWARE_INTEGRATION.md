# MITRA Hardware Integration Guide

This file is for the hardware engineer and anyone giving this repository to an AI assistant as supporting context for hardware or firmware work.

## Current App Expectations

The Android app supports two video paths:

| Path | When used | App expectation |
|---|---|---|
| MITRA hardware RTSP | MITRA WiFi is available | Device exposes a live RTSP stream |
| Phone back camera | MITRA hardware is not available | App falls back locally |

## WiFi Contract

| Item | Current value |
|---|---|
| Hardware SSID | `MITRA_DEVICE` |
| Hardware IP expected by app | `10.42.0.1` |
| RTSP URL | `rtsp://10.42.0.1:8554/stream` |
| App behavior | Scan for MITRA WiFi, connect, then open hardware stream |

If firmware changes SSID, IP address, port, authentication, or stream path, update this file and the Android code in the same branch.

## Video Stream Contract

The Android app expects the hardware to provide a stable camera stream that can be decoded by LibVLC in `VideoActivity`.

Current assumptions:

- Stream protocol: RTSP
- Stream host: `10.42.0.1`
- Stream port: `8554`
- Stream path: `/stream`
- First app transport attempt: RTSP over TCP
- Fallback transport: LibVLC automatic/UDP mode
- Current Android LibVLC cache target: 60 ms for network/live/RTSP cache
- Long-run mitigation: Android refreshes the live RTSP player after 5 minutes to clear possible decoder/RTSP backlog
- App decoded sample format: `ARGB_8888` bitmap copied from the displayed `SurfaceView`
- App sample cadence for the decoded frame cache: about 2.2 FPS by default
- Cloud upload format: JPEG, quality 70, MessagePack WebSocket payload
- Cloud upload cadence: fixed 1 FPS
- App may sample frames at a low cadence for local AI inference while the preview continues normally.
- If the stream is unavailable, the app should fail gracefully and use the phone camera fallback where possible.
- App-reported latest-frame age is not true camera latency; hardware should provide a timestamp overlay or embedded source timestamp for exact latency testing.

For the full hardware stream contract, Android decode path, cloud payload shape, and QA checklist, see `docs/HARDWARE_STREAM_CONTRACT_AND_QA.md`.

## AI And Frame Pipeline

The app samples frames from the live stream/camera and sends them through the local hazard engine.

Current high-level pipeline:

```text
frame -> day/night gate -> scene classifier -> hazard features -> spoken/logged guidance
```

Hazard features currently documented in the app flow:

- Fire/smoke
- Wet/dry
- Pothole
- Electric pole
- Pedestrian
- OCR through ML Kit

The active model files live in:

```text
app/src/main/assets/models/
```

The active model manifest is:

```text
app/src/main/assets/models/manifest.json
```

If the hardware changes camera orientation, resolution, compression, exposure behavior, timestamping, or frame cadence, document the expected impact here because it can affect AI detection quality.

Current user-facing local alert behavior:

- Android filters local detections with higher spoken-alert thresholds before TTS.
- Current spoken thresholds: fire/smoke 0.80, wet/dry 0.70, pothole 0.70, electric pole 0.70, pedestrian 0.35.
- Round-robin detector results are cached for at most 2500 ms.
- NIGHT clears cached detections.
- INDOOR clears cached pothole and electric-pole detections.

## Hardware Engineer Checklist

Before changing firmware or hardware stream behavior, check:

- `README.md` for the current app overview
- `WORKFLOW.md` for the full app flow
- `STREAMING_METADATA_SCHEMA.md` for streaming metadata expectations
- `app/src/main/AndroidManifest.xml` for Android permissions and services
- `app/src/main/java/com/unique/visionmate/VideoActivity.kt` for RTSP stream handling
- `app/src/main/java/com/unique/visionmate/RtspFrameSource.kt` for LibVLC decode and PixelCopy sampling
- `app/src/main/java/com/unique/visionmate/CameraActivity.kt` for phone-camera fallback behavior

After changing hardware behavior, update:

- This file
- `WORKFLOW.md`
- `docs/WORK_LOG.md`
- Any app code or tests affected by the new hardware behavior

## Open Hardware Details To Confirm

These details should be filled in as the hardware implementation becomes stable:

- Camera module model and output resolution
- RTSP encoder settings
- Expected stream FPS
- Expected boot time before WiFi/RTSP is available
- Power behavior during long sessions
- Thermal behavior during continuous streaming
- Any firmware OTA or config mechanism
