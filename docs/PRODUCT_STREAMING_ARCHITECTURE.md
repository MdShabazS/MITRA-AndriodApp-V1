# MITRA Product Streaming Architecture

Owner: MdShabazS / Codex

Status: Product direction document. Hardware code is not changed from this repo.

Last updated: 2026-08-12

## Goal

MITRA must behave like a product, not a lab demo:

- The live feed must not freeze while counters keep increasing.
- The decoded image must stay sharp enough for human viewing and AI inference.
- Android must work across OPPO, Poco, Samsung, Vivo/iQOO, Pixel/AOSP-like, OnePlus, and future phones.
- Future iOS support must be planned before locking the hardware protocol.
- Hardware, Android, and future iOS must all document their stream contract and keep rollback backups.

## Current Shipping Path

Current hardware stream:

- WiFi SSID: `MITRA_DEVICE`
- Hardware IP: `10.42.0.1`
- RTSP URL: `rtsp://10.42.0.1:8554/stream`
- Android decoder: LibVLC through `RtspFrameSource`
- Android sampled-frame bus: `VideoFrameCache`
- App sample format: `ARGB_8888` after PixelCopy
- Cloud upload format: JPEG over MessagePack WebSocket

Current Android safeguards:

- Low LibVLC cache target for RTSP/network/live playback.
- Last-good RTSP transport memory.
- Adaptive stale-frame refresh.
- Visual-freeze watchdog when copied frames keep arriving but the image fingerprint does not change.
- One canonical decoded frame bus so UI, local AI, OCR, and cloud read the same frame.

## Product-Level Target

The product stream should become a versioned contract between hardware and apps:

```text
hardware camera
  -> hardware encoder
  -> product stream transport
  -> Android/iOS decoder
  -> single decoded frame bus
  -> UI + local AI + OCR + cloud upload
```

The app must never infer stream health from only one signal. Product health requires:

- Player state.
- Decoded-frame timestamp.
- Visual fingerprint freshness.
- Frame counter or RTP timestamp freshness from hardware.
- Encoder profile and GOP/keyframe settings.
- Cloud send and reply telemetry.

## Protocol Decision

### Current v1

Keep RTSP/H.264 for the current Android build until Rahil captures the exact hardware encoder settings and we finish cross-phone validation.

Required v1 hardware settings to document and stabilize:

- H.264 video only for the low-latency preview path.
- Constant resolution, target `640 x 480` or `1280 x 720` after testing thermals.
- Target `15 fps` for product preview unless hardware proves stable at higher FPS.
- Short GOP/keyframe interval: `1s` target.
- SPS/PPS sent at stream start and repeated with keyframes when possible.
- No B-frames for the low-latency profile.
- Stable bitrate or capped VBR so WiFi jitter does not create large decode queues.
- Monotonic frame index and capture timestamp in a side metadata channel or overlay test mode.
- Hardware stream must remain compatible with Android now and future iOS; avoid Android-only encoder/protocol behavior.

### Recommended Product Candidate

Evaluate WebRTC/H.264 as the long-term Android + iOS + hardware streaming path.

Why:

- Native Android and iOS implementations exist.
- It is designed for real-time media.
- It includes jitter buffering, timing, packet loss recovery, and congestion-control concepts that RTSP-only stacks often leave to each player.
- It can carry a synchronized data channel for frame metadata, ACKs, health status, battery/thermal state, and encoder profile.

Tradeoff:

- Hardware firmware is more complex than RTSP.
- A local signaling path is required, even on direct WiFi.
- Rahil must validate CPU/thermal load on the hardware board.

### Alternatives To Reject Unless Testing Proves Better

- MJPEG over HTTP: easy to debug but bandwidth-heavy, often blurry under compression, and inefficient for battery/thermal.
- HLS/LL-HLS: strong ecosystem for media playback, but not ideal for assistive low-latency camera guidance.
- Raw UDP frames: low overhead but too fragile without a jitter/ordering/recovery layer.
- App-specific custom TCP frame stream: possible, but we would be rebuilding pieces that WebRTC already gives.

## Backup-First Rule

Before any hardware or app streaming change:

1. Create a Git branch or zip backup of the current Android repo.
2. Rahil must create a full backup of hardware code, configs, service files, and boot scripts.
3. Record current hardware stream facts in `docs/HARDWARE_DEVICE_PROFILE.md`.
4. Run a 15-minute baseline test in VLC and MITRA Android.
5. Save logs before changing firmware.

No product-stream change should proceed without rollback.

## Hardware Information Rahil Must Capture

Create or update `docs/HARDWARE_DEVICE_PROFILE.md` with:

- Hardware board model, OS image, camera module, camera driver.
- Exact encoder command or service code.
- Stream protocol, IP, port, path, and transport.
- Codec, profile, level, pixel format, resolution, FPS.
- Bitrate mode, target bitrate, max bitrate.
- GOP/keyframe interval, B-frame setting, SPS/PPS behavior.
- Latency settings and queue sizes.
- Thermal observations after 5, 10, and 15 minutes.
- VLC test result at 1, 5, 10, and 15 minutes.
- Android MITRA test result at 1, 5, 10, and 15 minutes.
- Source code backup path or commit hash.

## Android Requirements

Android must:

- Keep one canonical decoder and one canonical frame bus.
- Show stream health on the left side of the live screen.
- Show cloud send/reply health on the right side of the live screen.
- Prefer the last working transport first, then fall back safely.
- Detect stale frame age and visual freeze separately.
- Expose build tags and reconnect reasons in logcat.
- Save logs for every phone test.
- Never let cloud delay block live preview or local navigation.
- Do not prepare or send cloud frame payloads while the WebSocket is disconnected; resume upload only when the WebSocket is connected.

## Future iOS Requirements

iOS should not be treated as a clone after Android is done. It needs the same contract:

- Same hardware protocol and stream profile.
- Same health metrics: player state, decoded-frame age, visual freshness, cloud counters.
- Same frame metadata contract.
- Same fallback policy when cloud is unavailable.
- Same 15-minute phone validation matrix.

If RTSP remains the stream protocol, iOS will need a reliable third-party native player stack. If WebRTC is selected, iOS should use the same signaling and metadata contract as Android.

## Acceptance Gates

A stream profile is product-ready only when all pass:

- 15-minute fixed-camera test: no frozen image while counters increase.
- 15-minute motion test: finger-near-camera delay remains stable.
- 15-minute thermal test: no runaway latency after hardware warms up.
- Phone matrix test: OPPO, Poco, Samsung, Vivo/iQOO, Pixel/AOSP-like, OnePlus.
- Decode quality test: no persistent blur, smearing, or frame corruption.
- Recovery test: unplug/replug hardware stream and verify automatic reconnect.
- Cloud-off test: app keeps local navigation working without server.
- Cloud-on test: frames sent, ACKs, replies, and navigation decisions are visible in the UI.

## Current Decision

Do not rewrite hardware immediately.

Next action:

- Keep the current RTSP/H.264 Android path.
- Add product-grade stream/cloud status panels in Android.
- Rahil should document the current hardware stream profile and backup the hardware code by following `docs/RAHIL_CODEX_START_GUIDE.md`.
- Rahil should tune only toward the product H.264 profile first: 640 x 480, 15 FPS, 1-second GOP, no B-frames, repeated SPS/PPS when possible, capped/stable bitrate, bounded queues, timestamp/frame counter proof, and Android+iOS compatibility notes.
- After that, compare stabilized RTSP/H.264 against a WebRTC/H.264 prototype before committing future Android+iOS hardware protocol.

## References

- Android Media3 RTSP documentation confirms H.264 RTSP playback support and RTP over UDP/TCP behavior: https://developer.android.com/media/media3/exoplayer/rtsp
- Apple AVFoundation is the native Apple media framework for playback, capture, and stream processing: https://developer.apple.com/documentation/avfoundation
- WebRTC Native APIs are designed for real-time native audio/video applications: https://webrtc.github.io/webrtc-org/native-code/native-apis/
