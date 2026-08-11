# Rahil Codex Start Guide

Audience: Rahil / Hardware Engineering

Purpose:

- Tell Rahil exactly how to start a new Codex session using this repository.
- Tell future Codex how to guide Rahil step by step.
- Keep the hardware work aligned with the MITRA Android product contract.

## What Rahil Should Do First

1. Ask MdShabazS to add you as a collaborator on:

```text
https://github.com/MdShabazS/MITRA-AndriodApp-V1
```

2. Open Codex on your laptop.
3. Give Codex this repository as the reference repo:

```text
https://github.com/MdShabazS/MITRA-AndriodApp-V1
```

4. Tell Codex to read this file first:

```text
docs/HARDWARE_FIRMWARE_REBUILD_SPEC.md
```

5. Paste this prompt:

```text
I am Rahil, so let's start.

I am the hardware engineer for MITRA. First read docs/HARDWARE_FIRMWARE_REBUILD_SPEC.md, then guide me step by step to tune or rebuild the hardware camera/RTSP stream. Do not change the Android contract unless the app owner approves it. Ask me for my board, camera, encoder, RTSP server, and current firmware details, then give me the first exact action to perform.
```

## What Codex Should Do When Rahil Starts

Future Codex should not jump directly into rewriting firmware.

Use this order:

1. Read `docs/HARDWARE_FIRMWARE_REBUILD_SPEC.md`.
2. Read `docs/HARDWARE_INTEGRATION.md`.
3. Read `docs/HARDWARE_STREAM_CONTRACT_AND_QA.md`.
4. Confirm the non-negotiable Android contract:
   - SSID: `MITRA_DEVICE`
   - hardware IP: `10.42.0.1`
   - RTSP port: `8554`
   - RTSP path: `/stream`
   - full URL: `rtsp://10.42.0.1:8554/stream`
5. Ask Rahil for:
   - hardware board/model
   - OS/image version
   - camera module/model
   - camera driver or capture stack
   - encoder name and current settings
   - RTSP server name/version
   - whether GStreamer, FFmpeg, MediaMTX, live555, v4l2, or a vendor stack is used
   - current stream resolution/FPS/bitrate/GOP/B-frame settings
   - whether timestamp overlay or frame counter already exists
6. Tell Rahil to back up current firmware/config before changing anything.
7. Add timestamp overlay/frame counter.
8. Run Mac `ffprobe` / `ffplay` tests.
9. Tune encoder and RTSP settings.
10. Rebuild from scratch only if tuning cannot meet the product gates.

## First Questions Codex Should Ask Rahil

Codex should ask these before giving platform-specific commands:

```text
1. Which hardware board are you using?
2. Which OS/image is running on it?
3. Which camera module and camera driver are used?
4. How is RTSP currently served: GStreamer, FFmpeg, MediaMTX, live555, custom code, or vendor tool?
5. What are the current codec, resolution, FPS, bitrate, GOP/keyframe interval, and B-frame settings?
6. Can you add a timestamp overlay or frame counter to each video frame?
7. Can your laptop connect to MITRA_DEVICE WiFi and run ffprobe/ffplay?
```

## Product Goal Rahil Is Solving

Rahil is not just making video visible.

Rahil is making a product-level live vision feed for an assistive navigation app.

The hardware work should solve:

- delay growing from about 2-3 seconds to about 8 seconds
- Android decoder/render stutter
- blocky/color stream corruption
- stale camera frames being analyzed by Android AI
- unstable person/wet/pothole/fire guidance caused by delayed or corrupted frames
- reconnect requiring manual hardware intervention
- lack of proof for true camera-to-phone latency

## What Must Not Be Changed Without App Approval

Do not change these unless MdShabazS/app team approves and Android is retested:

- `MITRA_DEVICE`
- `10.42.0.1`
- RTSP port `8554`
- RTSP path `/stream`
- RTSP URL `rtsp://10.42.0.1:8554/stream`
- basic H.264 Android compatibility
- physical camera orientation
- authentication behavior

## Definition Of Done

Rahil should only call hardware ready after:

- timestamp overlay/frame counter exists
- Mac `ffprobe` confirms expected codec/resolution/FPS
- Mac `ffplay` 10-minute test passes
- Android MITRA 10-minute test passes
- camera-to-phone delay does not grow
- no repeated visual corruption appears
- reconnect works without hardware reboot
- encoder and RTSP settings are documented
- evidence is sent back to MdShabazS/app team

## Evidence Rahil Should Return

Create and share:

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
delay_screenshots_or_video_0_2_5_10min
known_issues.txt
```

Minimum evidence:

- board/model
- camera module
- RTSP server
- encoder
- codec
- resolution
- FPS
- bitrate
- GOP/keyframe interval
- B-frame setting
- timestamp overlay proof
- 10-minute Mac result
- 10-minute Android result

## Short Message MdShabazS Can Send Rahil

```text
Rahil, I added you as collaborator to this repo:
https://github.com/MdShabazS/MITRA-AndriodApp-V1

Open Codex on your laptop, give it this repo as reference, and first ask it to read:
docs/HARDWARE_FIRMWARE_REBUILD_SPEC.md

Then paste:
"I am Rahil, so let's start."

Codex should guide you step by step. The main goal is to make the MITRA hardware stream product-level: low latency, no growing delay, no corruption, correct timestamps, and stable RTSP for Android.
```
