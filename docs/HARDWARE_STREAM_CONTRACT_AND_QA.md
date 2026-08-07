# MITRA Hardware Stream Contract And QA

Owner: MdShabazS / Codex

Purpose:

- Document how the MITRA hardware camera feed reaches the Android app.
- Document how decoded frames are sampled for local AI and cloud upload.
- Record physical hardware stream QA evidence as tests are run.

## Current Hardware Feed Contract

| Item | Current value |
|---|---|
| Hardware WiFi SSID | `MITRA_DEVICE` |
| Hardware IP expected by app | `10.42.0.1` |
| RTSP port | `8554` |
| RTSP path | `/stream` |
| Full RTSP URL | `rtsp://10.42.0.1:8554/stream` |
| Android entry point | `VideoActivity` |
| Decoder | LibVLC |
| First transport attempt | Last successful transport from app memory, else RTSP over TCP |
| Fallback transport | Alternate between RTSP over TCP and LibVLC automatic/UDP mode |
| Audio handling | Disabled in LibVLC with `--no-audio` |
| Decode/display surface | Android `SurfaceView` |
| AI/cloud frame source | `VideoFrameCache`, populated from the same visible `SurfaceView` via `PixelCopy` |

The app expects the hardware to expose a stable RTSP stream on the MITRA WiFi network. The Android app binds its process to the MITRA WiFi network before opening RTSP so that `10.42.0.1` routes to the hardware instead of mobile data or another WiFi route.

## Android Stream Pipeline

```text
MITRA hardware camera
  -> hardware RTSP server at rtsp://10.42.0.1:8554/stream
  -> Android VideoActivity binds to MITRA WiFi
  -> RtspFrameSource opens RTSP with LibVLC
  -> LibVLC renders decoded video to SurfaceView
  -> PixelCopy samples the SurfaceView into ARGB_8888 bitmaps
  -> VideoFrameCache stores the latest canonical frame
  -> EngineBridge reads VideoFrameCache for local hazard inference
  -> BackgroundService reads VideoFrameCache for 1 FPS cloud upload and local-only navigation fallback
```

There is one canonical decoded frame bus: `VideoFrameCache`. The visible preview, local AI inference, OCR, and cloud upload all read from that same latest-frame cache.

## Incoming Feed Handling In Android

`RtspFrameSource` currently uses these RTSP/decoder settings:

- LibVLC options:
  - `--no-audio`
  - `--network-caching=150`
  - `--live-caching=150`
  - `--clock-jitter=0`
  - `--clock-synchro=0`
  - `--drop-late-frames`
  - `--skip-frames`
- Media options:
  - `:network-caching=150`
  - `:live-caching=150`
  - `:clock-jitter=0`
  - `:clock-synchro=0`
  - `:drop-late-frames`
  - `:skip-frames`
  - `:rtsp-tcp` when the selected transport is TCP
- First frame timeout:
  - TCP first-frame stall threshold: 3200 ms
  - UDP/auto first-frame stall threshold: 2800 ms
- Live stream stall threshold after frames start: 4000 ms
- Reconnect delay: 450 ms
- Transport memory:
  - The first transport that produces a captured frame is saved in app preferences.
  - On the next hardware stream launch, Android tries that saved transport first.
  - If the saved transport fails, the normal alternate-transport fallback still runs.

Current decoded sample settings:

- PixelCopy destination size: 640 x 480 by default.
- PixelCopy bitmap format: `ARGB_8888`.
- Sampling interval: 450 ms by default, about 2.2 sampled frames/second.
- Thermal behavior:
  - Critical thermal status doubles the sample interval.
  - Emergency thermal status quadruples the sample interval.
- Status UI reports sampled frame count, sampled FPS, sampled frame size, and latest frame age.

Important distinction:

- Hardware RTSP display can decode/render at the hardware stream's native cadence.
- Android AI/cloud sampling is intentionally lower cadence.
- Cloud upload is currently fixed at 1 FPS, even if RTSP decode/sample FPS is higher.

## Local AI Frame Path

`VideoActivity` attaches the local hazard engine after `VideoFrameCache` has a fresh frame. The engine reads copied ARGB frames from `VideoFrameCache`.

Features currently expected from the local pipeline:

- Day/night gate
- Scene classification
- Fire/smoke
- Wet/dry
- Pothole
- Electric pole
- Pedestrian
- OCR

The app status labels this path as "Local 1 FPS" because guidance/cloud cadence is 1 FPS. The RTSP sampling status separately shows the `VideoFrameCache` sampled FPS.

## Cloud Upload Format

`BackgroundService` reads the latest decoded frame from `VideoFrameCache`, compresses it to JPEG, and sends it over WebSocket as a MessagePack map.

Current cloud frame cadence:

- Fixed at 1 FPS by `getStreamingFps()`.
- One frame may be held while waiting for cloud ACK.
- If no fresh frame is available, the app logs `No video frame available` and waits.

Current JPEG encoding:

- Compression: JPEG
- Quality: 70
- Source bitmap: latest decoded frame from `VideoFrameCache`

Top-level MessagePack fields:

| Key | Type | Meaning |
|---|---|---|
| `protocol` | string | Current value: `mitra.frame.v1` |
| `device_id` | string | Sanitized MITRA device/user code |
| `session_id` | string | Android stream session id |
| `timestamp` | float | Unix timestamp in seconds |
| `seq` | int | Per-session frame sequence |
| `frame_index` | int | Same sequence value for compatibility |
| `frame` | binary | JPEG bytes |
| `meta` | map | Frame metadata |
| `sensor` | map/list/null | Local sensor payload, currently from local frame processor |
| `mobile_feature_outputs` | array/map | Local Android model outputs |

Current `meta` fields:

| Key | Type | Meaning |
|---|---|---|
| `width` | int | JPEG/source bitmap width |
| `height` | int | JPEG/source bitmap height |
| `fps` | int | Current cloud upload FPS, fixed at `1` |
| `format` | string | Current value: `jpeg` |
| `device_type` | string | Current value: `android` |
| `source` | string | Current value: `hardware_camera` |
| `scene_type` | string | Local scene result, default `outdoor` |
| `day_night` | string | Local day/night result, default `day` |

## Hardware/Firmware Details Still To Confirm

These values must be captured from the hardware implementation or field test logs:

- Camera module model.
- Hardware encoder codec, for example H.264/H.265/MJPEG.
- Hardware stream native resolution.
- Hardware stream native FPS.
- RTSP server implementation.
- RTSP startup time after hardware boot.
- Whether timestamps are generated by the camera, encoder, or RTSP server.
- Bitrate and GOP/keyframe interval.
- Orientation and lens mounting direction.
- Low-light exposure behavior.
- Thermal/power behavior during a 10-minute continuous stream.

## QA Test Plan

Before test:

- Power on MITRA hardware.
- Confirm the phone can see or connect to MITRA WiFi.
- Install the current debug APK.
- Clear logcat.

ADB launch note:

`VideoActivity` is not exported, so do not start it directly from shell on production/debug installs unless the manifest is changed for a special test build. Use the normal app entry flow:

```bash
adb shell am force-stop com.unique.visionmate
adb logcat -c
adb shell am start -n com.unique.visionmate/.MainActivity
# Tap START MITRA on the phone, or trigger the same flow through the app UI.
```

Log tags to watch:

```bash
adb logcat -v time -s VideoActivity RtspFrameSource HAZARD EngineBridge VOICE_BG AndroidRuntime ActivityManager ActivityTaskManager
```

Pass criteria:

- `VideoActivity` binds to MITRA WiFi or uses the held MITRA WiFi network.
- `RtspFrameSource` starts LibVLC with `rtsp://10.42.0.1:8554/stream`.
- LibVLC reaches `Playing`.
- First frame is captured through PixelCopy.
- `VideoFrameCache` status shows non-zero sampled frames, sampled FPS, frame size, and fresh last-frame age.
- `EngineBridge` attaches to the canonical frame source.
- Local `HAZARD` / `VideoActivity engine result` logs appear.
- If cloud upload is configured, `VOICE_BG frame_sent` logs appear at 1 FPS or slower when ACK-gated.

Fail signals:

- `wifi-bind-failed`
- `waiting-wifi` for all retry attempts
- `error:vlc-tcp` and `error:vlc-auto/udp`
- `watchdog: no captured frame`
- `No video frame available` repeatedly after RTSP is live
- `AndroidRuntime` crash

## Physical QA Evidence

Date: 2026-08-07

Status: PASS for hardware RTSP video path with local inference. Cloud endpoint was not reachable from the MITRA WiFi route during this run, so cloud response/ACK behavior remains partially validated.

Test setup:

- Phone model: OPPO CPH2729 / CPH2729IN
- Android version / SDK: Android SDK 36
- ADB device id: `92edef11`
- Hardware SSID observed: `MITRA_DEVICE`
- Hardware BSSID observed: `88:a2:9e:89:9a:48`
- Phone IP on MITRA WiFi: `10.42.0.168`
- WiFi frequency: 2462 MHz
- WiFi RSSI during evidence capture: about -37 dBm
- WiFi link speed during evidence capture: 72 Mbps

Observed startup timeline:

- `16:42:47.265`: phone completed connection to `MITRA_DEVICE`.
- `16:42:49.317`: LibVLC opened RTSP over TCP.
- `16:42:49.481`: LibVLC reached `Playing` on TCP, but PixelCopy did not receive a usable frame.
- `16:42:54.942`: watchdog reported no captured frame for 6336 ms and switched transport.
- `16:42:55.868`: LibVLC restarted with automatic/UDP transport.
- `16:42:55.915`: LibVLC reached `Playing` on automatic/UDP transport.
- `16:42:58.161`: first frame captured through PixelCopy at 640 x 480.
- `16:42:58.404`: first cloud frame send attempt logged, `seq=1`, JPEG size 13740 bytes.
- `16:42:59.231`: local hazard engine attached to the canonical `VideoFrameCache` source.
- `16:43:49.809`: `EngineBridge` reported 50 submitted frames.
- `16:43:50.532`: cloud frame send attempt logged through `seq=20`.

Observed stream values:

| Field | Observed value |
|---|---|
| RTSP URL | `rtsp://10.42.0.1:8554/stream` |
| Working transport | LibVLC automatic/UDP fallback |
| TCP behavior | Player reached `Playing`, but no PixelCopy frame arrived before watchdog timeout |
| Sampled frame size | 640 x 480 |
| Sampled bitmap format | `ARGB_8888` after PixelCopy |
| Sample publish interval | 450 ms default in `RtspFrameSource` |
| Cloud upload FPS | 1 FPS configured in `BackgroundService` |
| Cloud upload image format | JPEG quality 70 |
| Local inference | PASS; scene/day-night/hazard pipeline ran from hardware frames |
| Navigation fallback | PASS; local-only navigation produced WALK/WAIT/CAUTION/STOP decisions from local frames |

Important log evidence:

```text
LibVLC opening transport=vlc-tcp
LibVLC playing transport=vlc-tcp
watchdog: no captured frame for 6336ms; reconnecting
no RTSP frames yet; retrying with transport=vlc-auto/udp
starting LibVLC player transport=vlc-auto/udp url=rtsp://10.42.0.1:8554/stream
LibVLC playing transport=vlc-auto/udp
first frame captured 640x480 (PixelCopy)
frame_sent seq=1 jpeg_size=13740 send_latency=18ms
engine attached (gen=1, frame source: VideoActivity...)
EngineBridge submitted 50 frames
frame_sent seq=20 jpeg_size=12841 send_latency=1ms
```

Cloud/WebSocket note:

- Frame send attempts were logged, but the configured endpoint `ws://151.185.32.13:8765/ws` failed to connect from the MITRA WiFi route:

```text
WebSocket failure: failed to connect to /151.185.32.13 (port 8765) from /10.42.0.168
```

- Because of that, hardware RTSP decode, frame capture, local inference, local navigation, and outbound frame packaging are validated, but cloud ACK/response handling still needs a network setup where the phone can reach both MITRA hardware and the cloud endpoint.

Remaining hardware details to capture from firmware/hardware engineer:

- Native camera codec and encoder settings.
- Native RTSP resolution and FPS before Android PixelCopy sampling.
- RTSP server implementation and bitrate/GOP settings.
