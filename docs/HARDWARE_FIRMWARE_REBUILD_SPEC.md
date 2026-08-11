# MITRA Hardware Firmware Rebuild Spec

Owner: Rahil / Hardware Engineering

Purpose:

- Give the hardware engineer a clear contract if the MITRA camera firmware/RTSP stack must be reset or rebuilt.
- Prevent a blind rewrite that breaks the Android app contract.
- Define low-latency encoder/RTSP requirements, test commands, and pass/fail evidence.

## Decision Rule

Do not reset and code the hardware from scratch as the first step.

Use this order:

1. Back up the current working firmware/config.
2. Measure current stream latency with timestamp overlay or frame counter.
3. Test the stream on Mac with VLC/ffplay and on Android with the MITRA app.
4. Tune encoder/RTSP settings.
5. Rebuild firmware only if latency still grows, timestamps are broken, or the current RTSP stack cannot meet the pass criteria below.

Reason:

- Current hardware already exposes RTSP video and Android can decode it.
- The defect is long-run latency growth and occasional visual corruption, not total stream failure.
- A full reset without evidence may remove working WiFi/RTSP behavior and make debugging slower.

## Android Contract That Must Not Break

| Item | Required value |
|---|---|
| WiFi SSID | `MITRA_DEVICE` |
| Hardware IP | `10.42.0.1` |
| RTSP port | `8554` |
| RTSP path | `/stream` |
| Full URL | `rtsp://10.42.0.1:8554/stream` |
| App decoder | Android LibVLC |
| Android first transport | Last-good remembered transport, else TCP |
| Android fallback | LibVLC automatic/UDP |
| Audio | Optional on hardware, but Android disables audio |
| Target decoded frame size | 640 x 480 is currently verified |

If Rahil changes SSID, IP, port, stream path, authentication, codec, or resolution, update Android docs and retest before calling the hardware ready.

## Product-Level Stream Requirements

The hardware stream must behave like a live navigation camera, not like a buffered video stream.

Required:

- Startup to first decodable frame: target under 3 seconds after Android opens RTSP.
- Long-run delay: must not grow over time.
- 10-minute test: camera-to-phone delay should remain stable and within the agreed product limit.
- FPS: stable 25 or 30 FPS preferred; 15 FPS acceptable only if intentionally chosen and documented.
- Resolution: 640 x 480 minimum for current app behavior.
- Codec: H.264 preferred for Android compatibility.
- Timestamps: monotonic and generated from the live capture clock.
- Keyframes: at least 1 keyframe per second.
- B-frames: disabled for low latency.
- RTSP server: must not build an unbounded queue when Android decode/render falls behind.
- Reconnection: Android should be able to disconnect/reconnect without rebooting hardware.

Current Android mitigation:

- LibVLC cache target is 60 ms.
- Android refreshes the RTSP session after 5 minutes to clear possible backlog.
- This is a mitigation, not a replacement for a correct low-latency hardware stream.

## Encoder Settings Rahil Should Prefer

Use these settings as the firmware target:

| Setting | Preferred value |
|---|---|
| Codec | H.264 baseline or constrained baseline |
| Resolution | 640 x 480 |
| FPS | 25 or 30 |
| Bitrate | Start around 1.0-2.0 Mbps for 640 x 480 |
| GOP/keyframe interval | 1 second |
| B-frames | 0 / disabled |
| Rate control | CBR or low-latency VBR |
| Encoder latency mode | Low latency / zerolatency if available |
| SPS/PPS | Sent with stream and repeated with keyframes if supported |
| Audio | Disable unless needed later |

For GStreamer-style pipelines, the intent is:

```text
camera -> colorspace/scale -> timestamp overlay -> H.264 encoder tuned for zerolatency
       -> RTP H264 payloader -> RTSP server at rtsp://10.42.0.1:8554/stream
```

For FFmpeg-style pipelines, the intent is:

```text
camera input
  - resolution 640x480
  - fps 25/30
  - h264
  - preset/tune low latency or zerolatency
  - gop 25/30
  - bf 0
  - no audio
  -> RTSP server /stream
```

Exact commands depend on the hardware platform and camera driver. Do not copy a command blindly if the board uses a different camera stack.

## Required Timestamp Overlay

Before any final pass/fail test, hardware must add one of these:

1. Visible timestamp overlay on every video frame.
2. Visible frame counter on every video frame.
3. Embedded source timestamp metadata that Android can read later.

Preferred first implementation:

```text
MITRA yyyy-mm-dd hh:mm:ss.SSS frame=<counter>
```

Why:

- Android's latest-frame age only measures when Android copied a displayed frame.
- It does not prove true camera-to-phone latency.
- A timestamp overlay lets app, hardware, and AI teams measure the real delay with a camera/stopwatch.

## Reset/Rebuild Procedure If Needed

If the current firmware cannot be tuned, Rahil can rebuild from scratch using this safe sequence.

1. Create a backup:

```text
firmware image
boot config
RTSP server config
camera config
encoder config
WiFi/AP config
known working commit/version
```

2. Rebuild only the camera stream path first:

```text
MITRA_DEVICE WiFi -> 10.42.0.1 -> RTSP /stream -> H.264 low-latency video
```

3. Do not add extra features until this passes:

- WiFi visible as `MITRA_DEVICE`
- Phone receives IP in `10.42.0.x`
- RTSP opens at `rtsp://10.42.0.1:8554/stream`
- VLC/ffplay on Mac receives stable video
- Android MITRA app receives stable video
- Timestamp overlay proves delay does not grow for 10 minutes

4. Only after stream stability passes, add any additional hardware functions.

## Mac-Side Test Commands

Run these while the Mac is connected to `MITRA_DEVICE` WiFi.

Probe stream:

```bash
ffprobe -hide_banner rtsp://10.42.0.1:8554/stream
```

Low-latency playback test:

```bash
ffplay -fflags nobuffer -flags low_delay -framedrop -rtsp_transport udp rtsp://10.42.0.1:8554/stream
```

TCP comparison:

```bash
ffplay -fflags nobuffer -flags low_delay -framedrop -rtsp_transport tcp rtsp://10.42.0.1:8554/stream
```

What to record:

- Time to first frame.
- Starting visual delay using timestamp overlay.
- Delay after 2 minutes.
- Delay after 5 minutes.
- Delay after 10 minutes.
- Whether frame corruption appears.
- Whether UDP or TCP performs better.

## Android Test With MITRA App

Before test:

- Install latest debug APK.
- Connect phone to MITRA hardware or let app connect.
- Clear logcat.
- Keep cloud testing skipped if the current test is local-only.

ADB log capture:

```bash
adb logcat -c
adb logcat -v time -s VideoActivity RtspFrameSource HAZARD EngineBridge VOICE_BG AndroidRuntime ActivityManager ActivityTaskManager > mitra_hardware_stream_test.log
```

App flow:

1. Open MITRA app.
2. Tap START or say `start mitra`.
3. Confirm hardware stream opens.
4. Keep camera pointed at a controlled scene.
5. Record delay with timestamp overlay at 0, 2, 5, and 10 minutes.
6. Save logs and screenshots.

Pass criteria:

- First frame appears quickly.
- Video delay does not grow during the 10-minute test.
- No visible blocky/color corruption.
- Android does not repeatedly reconnect except planned live refresh.
- Local inference logs continue.
- Static indoor room does not produce random pothole/wet/fire alerts.
- If a person is visible, person/pedestrian caution is consistent after fresh detection.

## Hardware Evidence Rahil Must Return

Rahil should add these to the repo or send them to the app team:

- Hardware board/model.
- Camera module model.
- Firmware version or commit.
- RTSP server name/version.
- Encoder name/version.
- Codec, resolution, FPS, bitrate.
- GOP/keyframe interval.
- B-frame setting.
- Timestamp source.
- Whether timestamp overlay is enabled.
- Mac VLC/ffplay 10-minute result.
- Android MITRA 10-minute result.
- Logs or screenshots showing delay at 0, 2, 5, and 10 minutes.

## Current Known Issue To Solve

Observed on 2026-08-11:

- Early live feed delay: about 2.5-2.9 seconds.
- Later delay after about 7 minutes: about 8 seconds.
- Android logs showed decoder/render stutter on OPPO.
- WiFi signal looked acceptable during the stutter.

Likely causes to check:

- Hardware encoder queue.
- RTSP server buffering.
- Incorrect or non-monotonic timestamps.
- Long GOP/keyframe interval.
- B-frames or delayed frames.
- Android decoder/render backlog triggered by stream timing.

Do not declare hardware complete until the timestamp-overlay test proves delay is stable.
