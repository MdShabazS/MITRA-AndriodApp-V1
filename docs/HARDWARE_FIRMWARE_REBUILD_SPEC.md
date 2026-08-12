# MITRA Product-Level Hardware Firmware And RTSP Stream Spec

Audience: Rahil / Hardware Engineering

Android app owner: MdShabazS

Purpose:

- Provide one complete hardware-side implementation guide for MITRA.
- Define how to tune the observed stream delay, decoder/render stutter, visual corruption, and AI false-alert amplification when evidence points to hardware.
- Define exactly what the Android app expects from the hardware.
- Define the stream baseline that must also support future iOS.
- Define when to tune the existing firmware and when to rebuild from scratch.
- Define product-level pass/fail tests before hardware is accepted.

This document is intended to be sent directly to Rahil. Follow it as the hardware contract unless the app team explicitly changes the Android contract in the repo.

## 0. Current Decision After Latest Android Retest

As of 2026-08-11, do not reset or recode the hardware from scratch as the next step.

Latest evidence:

- A 15-minute Android hardware-stream retest did not reproduce the earlier 8-second app delay.
- Android displayed fresh decoded frames at the recorded checkpoints.
- The app team found and fixed an Android RTSP repeated-refresh loop after that retest.

Current hardware action:

1. Preserve and document the current hardware implementation.
2. Back up/upload current hardware source code, startup scripts, and configs.
3. Add timestamp/frame-counter proof if missing.
4. Confirm exact stream format and RTSP/encoder settings.
5. Tune the current stream toward the MITRA product H.264 profile: 640 x 480 first, 15 FPS first, 1-second GOP/keyframe interval, no B-frames, repeated SPS/PPS when possible, capped/stable bitrate, and bounded queues that drop old frames.
6. Confirm the hardware stream remains compatible with Android now and future iOS.
7. Tune only if new evidence shows hardware-side latency growth, corruption, reconnect failure, timestamp problems, or cross-platform compatibility risk.

Use `docs/HARDWARE_AS_BUILT_CAPTURE_TEMPLATE.md` before changing firmware.

## 1. Current Problem Summary

During earlier local hardware testing on 2026-08-11, the Android app successfully connected to MITRA hardware WiFi and opened the RTSP stream, but the stream was not product-ready.

Observed issues:

1. Initial live-feed delay was already high: about 2.5-2.9 seconds during the first 0-4 minutes.
2. Delay grew to about 8 seconds after roughly 7 minutes.
3. Visible stream corruption appeared during the stability window.
4. Android/OPPO logs showed decoder/render stutter after the stream had been running.
5. The app sampled fresh rendered frames, but those rendered frames may already have been old camera frames.
6. Local AI guidance became unstable because delayed/corrupted/stale stream frames can produce incorrect hazard context.

Important distinction:

- Android `latest frame age` means Android recently copied the displayed video surface.
- It does not prove the camera frame is live.
- True camera-to-phone latency must be measured using a hardware timestamp overlay, frame counter, or embedded source timestamp.

Later 2026-08-11 update:

- The 8-second delay did not reproduce in the later 15-minute Android retest.
- Therefore the current plan is documentation, timestamp proof, and targeted tuning rather than full hardware recoding.

## 2. Product-Level Target

MITRA is an assistive navigation product. The stream is not just a preview. It is the input for hazard detection, voice guidance, and user safety.

Product behavior required:

- The stream must behave like a live camera, not a buffered media stream.
- Latency must be bounded and stable.
- Delay must not grow over time.
- Reconnects must work without hardware reboot.
- The app must be able to trust the frame order and timestamps.
- The hardware must preserve the Android WiFi and RTSP contract.
- The hardware stream must be designed for Android now and future iOS, not only one Android phone.

Product-level latency gate:

| Metric | Target | Maximum allowed before fail |
|---|---:|---:|
| First decodable frame after Android opens RTSP | <= 3.0 s | > 5.0 s |
| Camera-to-phone latency at start | <= 1.0 s preferred | > 2.0 s |
| Camera-to-phone latency after 10 minutes | <= start latency + 0.5 s | growth > 1.0 s |
| Frame corruption during 10-minute test | 0 visible corruption | repeated corruption |
| Reconnect after Android disconnects | recovers automatically | requires hardware reboot |

If the hardware cannot meet these gates with the current firmware, rebuild the stream stack using this document.

## 3. Decision: Tune First, Rebuild If Needed

Do not wipe and code from scratch as the first action.

Use this order:

1. Back up current firmware, boot config, WiFi config, camera config, encoder config, and RTSP config.
2. Add timestamp overlay or frame counter.
3. Measure current hardware stream on Mac using `ffprobe` and `ffplay`.
4. Measure current hardware stream on Android MITRA app.
5. Tune encoder and RTSP server settings.
6. If delay still grows, timestamps are broken, or corruption remains, rebuild the camera stream stack.

Why:

- Current hardware already exposes RTSP video.
- Android can already decode and sample the stream.
- The issue is stream freshness/stability, not total absence of hardware function.
- Blind reset can destroy working WiFi/RTSP behavior and waste debugging time.

When rebuild is allowed:

- Delay grows during a 10-minute test even after encoder/RTSP tuning.
- RTSP server cannot prevent backlog buildup.
- Encoder cannot disable delayed frames or B-frames.
- Source timestamps are missing, wrong, or non-monotonic.
- Reconnect requires hardware reboot.
- Visual corruption persists across Mac and Android tests.

## 4. Android Contract That Must Not Break

These values are mandatory unless the Android app is changed and retested in the same branch.

| Item | Required value |
|---|---|
| WiFi SSID | `MITRA_DEVICE` |
| Hardware AP IP | `10.42.0.1` |
| Phone IP range | `10.42.0.x` |
| RTSP port | `8554` |
| RTSP path | `/stream` |
| Full RTSP URL | `rtsp://10.42.0.1:8554/stream` |
| App decoder | Android LibVLC |
| Android first transport | Last-good remembered transport, else TCP |
| Android fallback | LibVLC automatic/UDP |
| Audio | Android disables audio |
| Minimum verified resolution | 640 x 480 |
| Camera purpose | Live assistive navigation and AI inference |
| Product compatibility | Android now, future iOS later |

Do not change:

- SSID
- IP address
- RTSP port
- RTSP path
- Authentication behavior
- Codec family
- Android + future iOS compatibility
- Orientation
- Resolution
- FPS

unless the app team is told first and Android is retested after the change.

## 5. Hardware Architecture Rahil Should Build

The hardware stream stack should be simple, bounded, and low latency.

Required architecture:

```text
Camera sensor
  -> capture driver
  -> timestamp/frame-counter overlay
  -> color conversion / resize to 640 x 480
  -> H.264 low-latency encoder
  -> RTP packetizer
  -> RTSP server at rtsp://10.42.0.1:8554/stream
  -> Android MITRA app over MITRA_DEVICE WiFi
  -> future iOS MITRA app using the same product stream contract or an approved successor contract
```

Design rules:

- Every queue must be bounded.
- If the consumer is slow, drop old frames, not new frames.
- Never allow an infinite or long video queue.
- Prefer live freshness over perfect frame preservation.
- Keep camera capture, encoding, and RTSP serving independent enough that one slow client does not freeze the whole camera stack.
- Make stream restart possible without rebooting the device.

## 6. WiFi/AP Requirements

The hardware must expose a stable local WiFi network.

Required:

- SSID: `MITRA_DEVICE`
- Hardware IP: `10.42.0.1`
- DHCP: enabled for phone client
- Phone should receive `10.42.0.x`
- RTSP must be reachable from the phone on `10.42.0.1:8554`
- WiFi should remain active during continuous streaming
- Hardware should recover if phone disconnects/reconnects

Recommended:

- 2.4 GHz is acceptable for compatibility.
- Keep channel stable unless interference requires change.
- Avoid captive portal behavior.
- Avoid changing SSID between boots.

Evidence Rahil must provide:

- Screenshot/log showing phone connected to `MITRA_DEVICE`.
- Hardware IP route confirmation.
- 10-minute WiFi stability note.

## 7. Camera Requirements

Required:

- Camera must output a stable image continuously for at least 10 minutes.
- Orientation must match the physical MITRA mounting direction.
- Exposure should not oscillate heavily in a fixed indoor room.
- Low-light behavior must be documented.
- Camera frame timestamp must come from the live capture time.

Recommended starting settings:

| Setting | Target |
|---|---|
| Resolution | 640 x 480 |
| FPS | 15 first; raise only if stable |
| Pixel format before encoder | NV12/NV21/YUV420 if supported |
| Exposure | Auto allowed, but must be stable |
| White balance | Auto allowed, but must be stable |
| Rotation | Fixed and documented |

Do not upscale low-quality input to 640 x 480 just to satisfy resolution. If the real camera output is lower quality, document it.

## 8. Encoder Requirements

H.264 is the required product baseline for Android compatibility and future iOS planning. H.265 is not the default MITRA stream codec.

Product-level encoder target:

| Setting | Required / preferred value |
|---|---|
| Codec | H.264 |
| Profile | Baseline or constrained baseline preferred |
| Resolution | 640 x 480 |
| FPS | 15 first; raise only after 15-minute thermal and phone tests pass |
| Bitrate | Start capped/stable, typically 0.8-1.5 Mbps for 640 x 480 at 15 FPS |
| GOP/keyframe interval | 1 second |
| B-frames | 0 / disabled |
| Encoder mode | Low latency / zerolatency |
| Rate control | CBR or low-latency VBR |
| SPS/PPS | Present at stream start, repeat with keyframes if supported |
| Timestamp behavior | Monotonic, live capture clock |
| Audio | Disable unless required later |

Critical:

- Disable B-frames. They add decode delay.
- Use a short GOP. Long GOP makes recovery slow and can worsen delay/corruption after packet loss.
- Do not let encoder output queue grow without bound.
- If the phone or RTSP client falls behind, drop old frames.
- Do not switch the product baseline to H.265 unless MdShabazS approves it after Android phone matrix testing, future iOS path validation, and hardware thermal testing.

## 9. RTSP Server Requirements

The RTSP server must be configured for live streaming.

Required:

- URL: `rtsp://10.42.0.1:8554/stream`
- Support at least one stable transport that Android can use.
- TCP should work if possible.
- UDP should work if possible.
- Reconnect should work repeatedly.
- Server must not buffer seconds of old frames.
- Server must use live timestamps.
- Server must close dead clients and free their queues.

Queue policy:

```text
If client is slow:
  drop old frames
  keep newest keyframe and newest delta frames
  never keep building delay
```

Bad behavior:

```text
camera live frame -> encoder -> RTSP queue -> old frames wait -> client sees delayed video
```

Correct behavior:

```text
camera live frame -> encoder -> bounded queue -> old frames dropped if needed -> client sees newest video
```

## 10. Timestamp Overlay Requirement

This is mandatory before final testing.

Add visible overlay on every frame:

```text
MITRA yyyy-mm-dd hh:mm:ss.SSS frame=<counter>
```

Minimum acceptable overlay:

```text
frame=<counter>
```

Preferred:

- Timestamp with milliseconds
- Monotonic frame counter
- Small readable text in one corner
- Does not cover the center navigation view

Why this matters:

- It proves real camera-to-phone latency.
- It reveals whether frames are arriving out of order.
- It shows whether delay grows over time.
- It lets app and hardware teams compare Mac VLC, Android MITRA app, and raw hardware behavior.

Do not remove the overlay until product latency is proven. It can later become a debug-only firmware option.

## 11. Rebuild Plan If Current Firmware Fails

If tuning cannot meet the product gates, rebuild only the camera stream path first.

Phase 1: Backup

Save:

```text
firmware image
boot config
WiFi/AP config
camera config
encoder config
RTSP server config
known working version or commit id
startup scripts/services
```

Phase 2: Minimum stream

Build only this:

```text
MITRA_DEVICE WiFi
10.42.0.1 static AP IP
camera capture
timestamp/frame overlay
H.264 low-latency encoder
RTSP server /stream on port 8554
```

Do not add:

- Extra AI processing on hardware
- Cloud upload
- Audio
- Web UI
- OTA
- Extra services

until the stream passes the 10-minute latency test.

Phase 3: Reliability

Add:

- process/service watchdog
- reconnect handling
- stream restart without board reboot
- temperature/power logging if available
- boot-to-stream-ready measurement

Phase 4: Product extras

Only after stream passes:

- firmware version reporting
- battery/temperature status
- debug endpoint
- OTA/config mechanism

## 12. Example Pipeline Intent

Exact commands depend on hardware platform. These are intent examples, not copy-paste final code.

GStreamer-style intent:

```text
camera source
  -> fixed caps 640x480 at 25/30 fps
  -> timestamp overlay
  -> H.264 encoder tune=zerolatency bframes=0 key-int-max=fps
  -> H264 RTP payloader config-interval=1
  -> RTSP server mount /stream on port 8554
```

FFmpeg-style intent:

```text
camera input
  - video_size 640x480
  - framerate 25/30
  - codec h264
  - tune zerolatency
  - preset ultrafast/veryfast if available
  - gop 25/30
  - bf 0
  - no audio
  -> RTSP /stream
```

If the board has hardware encoder support, prefer the hardware encoder only if it supports low-latency mode and correct timestamps.

## 13. Mac Test Procedure

Connect Mac to `MITRA_DEVICE` WiFi.

Probe:

```bash
ffprobe -hide_banner rtsp://10.42.0.1:8554/stream
```

UDP low-latency playback:

```bash
ffplay -fflags nobuffer -flags low_delay -framedrop -rtsp_transport udp rtsp://10.42.0.1:8554/stream
```

TCP low-latency playback:

```bash
ffplay -fflags nobuffer -flags low_delay -framedrop -rtsp_transport tcp rtsp://10.42.0.1:8554/stream
```

Record:

| Time | Delay from overlay | FPS feel | Corruption? | Notes |
|---|---:|---|---|---|
| 0 min | | | | |
| 2 min | | | | |
| 5 min | | | | |
| 10 min | | | | |

Mac pass criteria:

- First frame appears quickly.
- Delay does not grow.
- No repeated visible corruption.
- UDP and/or TCP is stable.
- ffprobe reports expected codec, resolution, and FPS.

If Mac fails, fix hardware first before blaming Android.

## 14. Android Test Procedure

Before test:

- Install latest MITRA debug APK from app team.
- Keep cloud testing skipped unless specifically testing cloud.
- Clear logcat.
- Charge phone above 50 percent if possible.
- Disable battery saver if possible.
- Keep hardware powered steadily.

ADB setup:

```bash
adb logcat -c
adb logcat -v time -s VideoActivity RtspFrameSource HAZARD EngineBridge VOICE_BG AndroidRuntime ActivityManager ActivityTaskManager > mitra_android_hardware_stream.log
```

App flow:

1. Open MITRA app.
2. Tap START or use `start mitra`.
3. Confirm app connects to `MITRA_DEVICE`.
4. Confirm stream opens.
5. Point camera at a controlled static indoor room scene.
6. Record timestamp overlay delay at 0, 2, 5, and 10 minutes.
7. Put a person in center view and check person caution after fresh detection.
8. Save logcat and screenshots.

Android pass criteria:

- App opens RTSP stream without manual retries.
- First frame appears within product gate.
- Delay does not grow during 10-minute run.
- No repeated blocky/color corruption.
- Android logs do not show repeated decoder/render stutter.
- Local inference logs continue.
- Static room does not randomly speak pothole/wet/fire.
- Real wet/pothole test scene speaks only after confirmed fresh detections.
- Person in center is not hidden by stale hazard detections.

## 15. Hazard-AI Impact Rahil Must Understand

The hardware does not run the current Android TFLite hazard models, but hardware frame quality directly controls AI quality.

Bad stream behavior causes AI issues:

- Delayed frames make voice guidance describe the past.
- Corrupted frames can look like hazards.
- Exposure flicker can trigger unstable classifications.
- Long buffering can make person detection inconsistent.
- Wrong orientation can break object zones.

Therefore hardware pass is not only "video visible." Product pass means:

```text
live, stable, correctly oriented, low-latency frames suitable for AI inference
```

## 16. Known Android-Side Mitigations Already Added

The Android app has already added mitigations, but hardware must still be correct.

Current Android behavior:

- LibVLC network/live/RTSP cache target: 60 ms.
- Android can remember last successful RTSP transport.
- Android falls back between TCP and LibVLC automatic/UDP.
- Android refreshes a running RTSP session after 5 minutes to clear possible backlog.
- Local detector cache expires after 2500 ms.
- NIGHT clears cached detections.
- INDOOR clears cached pothole and electric-pole detections.
- Local hazard speech requires confirmed fresh detections from real feature runs.

These mitigations cannot fully solve a hardware stream that keeps generating delayed, corrupted, or incorrectly timestamped frames.

## 17. Hardware Definition Of Done

Rahil can mark hardware stream ready only when all are true:

- Current firmware/config is backed up.
- WiFi contract is unchanged.
- RTSP URL is unchanged.
- Timestamp overlay or frame counter is available.
- Mac ffprobe confirms codec/resolution/FPS.
- Mac ffplay 10-minute test passes.
- Android MITRA 10-minute test passes.
- Delay does not grow over time.
- Reconnect works without hardware reboot.
- Visual corruption is not present in normal test conditions.
- Encoder settings are documented.
- RTSP server settings are documented.
- Logs/screenshots are shared with the app team.

## 18. Evidence Package Rahil Must Send Back

Create a folder or message with:

```text
hardware_board.txt
camera_module.txt
firmware_version_or_commit.txt
wifi_config.txt
rtsp_server_config.txt
encoder_config.txt
ffprobe_output.txt
mac_ffplay_10min_notes.txt
android_10min_notes.txt
screenshots_or_video_delay_0_2_5_10min
known_issues.txt
```

Minimum required values:

- Board/model
- Camera module
- RTSP server name/version
- Encoder name/version
- Codec
- Resolution
- FPS
- Bitrate
- GOP/keyframe interval
- B-frame setting
- Timestamp source
- Whether overlay is enabled
- UDP test result
- TCP test result
- Android test result

## 19. Fail Conditions

Hardware is not product-ready if any of these happen:

- Delay grows over time.
- Delay exceeds the product gate.
- Stream freezes or corrupts repeatedly.
- Android needs hardware reboot to reconnect.
- RTSP URL changes without app update.
- Timestamps are missing or not monotonic.
- B-frames are enabled and causing delay.
- GOP is too long for low-latency recovery.
- Static room produces unstable frames that amplify false AI alerts.
- Rahil cannot provide encoder/RTSP settings.

## 20. Final Instruction To Rahil

The goal is not just to make RTSP open. The goal is to deliver a product-level live vision feed for an assistive navigation app.

Build or tune the hardware so the Android app receives:

```text
stable WiFi
stable RTSP
low-latency H.264
bounded queues
correct timestamps
no growing delay
no repeated corruption
reconnect without reboot
timestamp proof for every final test
```

Do not declare hardware complete until both Mac and Android 10-minute tests pass with timestamp evidence.
