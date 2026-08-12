# Rahil Codex Start Guide

Audience: Rahil / Hardware Engineering

Purpose:

- Tell Rahil exactly how to start a new Codex session using this repository.
- Tell future Codex how to guide Rahil step by step.
- Keep the hardware work aligned with the MITRA Android product contract and future iOS product contract.
- Capture the current hardware implementation details and code so the team finally has a hardware source of truth.

## Current Decision After 2026-08-11 Retest

Do not reset or recode the hardware from scratch right now.

Reason:

- A 15-minute Android retest did not reproduce the earlier 8-second app delay.
- Android-side screenshots showed fresh displayed frames throughout the run.
- The app team found and fixed an Android RTSP refresh-loop bug after the test.

Rahil's immediate hardware task is now:

1. Document the current hardware exactly as built.
2. Back up and upload the current hardware code/configuration into this repo or an approved linked artifact.
3. Add timestamp/frame-counter proof if it is not already available.
4. Confirm the exact stream format, encoder settings, FPS, bitrate, GOP, transport, and RTSP server behavior.
5. Tune the hardware stream toward the MITRA product profile: H.264, 640 x 480 first, 15 FPS first, 1-second GOP/keyframe interval, no B-frames, repeated SPS/PPS when possible, capped/stable bitrate, and bounded queues that drop old frames.
6. Keep the stream contract compatible with Android now and future iOS. Do not choose Android-only hardware behavior.
7. Tune only the needed hardware settings if measured evidence shows latency, corruption, reconnect, timestamp, or cross-platform compatibility problems.

Full firmware rebuild is a fallback only, not the first action.

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
docs/RAHIL_CODEX_START_GUIDE.md
```

5. Paste this prompt:

```text
i m rahil lets start

I am the hardware engineer for MITRA. First read docs/RAHIL_CODEX_START_GUIDE.md, docs/HARDWARE_INTEGRATION.md, docs/HARDWARE_STREAM_CONTRACT_AND_QA.md, docs/HARDWARE_AS_BUILT_CAPTURE_TEMPLATE.md, docs/HARDWARE_FIRMWARE_REBUILD_SPEC.md, and docs/PRODUCT_STREAMING_ARCHITECTURE.md.

Important: the latest Android retest did not reproduce the earlier 8-second delay, so do not tell me to reset or recode the hardware from scratch first. Guide me step by step to document the existing hardware, back up/upload the current hardware code and config, confirm the exact stream format/FPS/bitrate/GOP/RTSP settings, add timestamp or frame-counter proof, and tune only what evidence shows is needed. The hardware must support Android now and future iOS, so prefer product-safe H.264 settings unless MdShabazS approves a protocol change. Do not change the Android contract unless MdShabazS approves it.
```

## What Codex Should Do When Rahil Starts

Future Codex should not jump directly into rewriting firmware.

Use this order:

1. Read `docs/RAHIL_CODEX_START_GUIDE.md`.
2. Read `docs/HARDWARE_INTEGRATION.md`.
3. Read `docs/HARDWARE_STREAM_CONTRACT_AND_QA.md`.
4. Read `docs/HARDWARE_AS_BUILT_CAPTURE_TEMPLATE.md`.
5. Read `docs/PRODUCT_STREAMING_ARCHITECTURE.md`.
6. Read `docs/HARDWARE_FIRMWARE_REBUILD_SPEC.md` only as fallback guidance.
7. Confirm the non-negotiable Android contract:
   - SSID: `MITRA_DEVICE`
   - hardware IP: `10.42.0.1`
   - RTSP port: `8554`
   - RTSP path: `/stream`
   - full URL: `rtsp://10.42.0.1:8554/stream`
8. Confirm the non-negotiable product requirement:
   - hardware stream must remain compatible with Android and future iOS
   - H.264 is the required codec baseline for now
   - H.265 must not replace H.264 unless Android phone matrix, future iOS path, hardware thermal load, and app decoding are all approved and retested
9. Ask Rahil for:
   - hardware board/model
   - OS/image version
   - camera module/model
   - camera driver or capture stack
   - encoder name and current settings
   - RTSP server name/version
   - whether GStreamer, FFmpeg, MediaMTX, live555, v4l2, or a vendor stack is used
   - current stream resolution/FPS/bitrate/GOP/B-frame settings
   - whether timestamp overlay or frame counter already exists
10. Tell Rahil to back up current firmware/config before changing anything.
11. Tell Rahil to create an as-built hardware documentation packet using `docs/HARDWARE_AS_BUILT_CAPTURE_TEMPLATE.md`.
12. Tell Rahil to upload the current hardware source/configs or a documented artifact link.
13. Add timestamp overlay/frame counter if missing.
14. Tune toward the product H.264 profile only after backup:
    - 640 x 480 first
    - 15 FPS first
    - 1-second GOP/keyframe interval
    - no B-frames
    - repeat SPS/PPS with keyframes if supported
    - capped/stable bitrate
    - bounded queues that drop old frames
15. Run Mac `ffprobe` / `ffplay` tests.
16. Run Android MITRA retest.
17. Record future iOS compatibility notes.
18. Rebuild from scratch only if tuning cannot meet the product gates.

## First Questions Codex Should Ask Rahil

Codex should ask these before giving platform-specific commands:

```text
1. Which hardware board are you using?
2. Which OS/image is running on it?
3. Which camera module and camera driver are used?
4. How is RTSP currently served: GStreamer, FFmpeg, MediaMTX, live555, custom code, or vendor tool?
5. What are the current codec, resolution, FPS, bitrate, GOP/keyframe interval, and B-frame settings?
6. Does the current stream use H.264, and can it be set to 640 x 480, 15 FPS, 1-second GOP, no B-frames, repeated SPS/PPS, and capped bitrate?
7. Can you add a timestamp overlay or frame counter to each video frame?
8. Can your laptop connect to MITRA_DEVICE WiFi and run ffprobe/ffplay?
9. Where is the current hardware code stored?
10. Can we copy the hardware source code, startup scripts, config files, and dependency/version list into this repo or a linked artifact?
11. Are there any secrets, WiFi passwords, private keys, or tokens in the hardware code that must be removed before upload?
12. Which parts of the hardware stream are Android-specific today, and what must be kept generic for future iOS?
```

## Product Goal Rahil Is Solving

Rahil is not just making video visible.

Rahil is making a product-level live vision feed for an assistive navigation app.

Current priority after the 2026-08-11 retest:

- Create the first complete hardware documentation packet.
- Preserve the current working hardware code/config before future changes.
- Add timestamp/frame-counter proof for true camera-to-phone latency.
- Confirm the exact stream format: codec, profile, resolution, FPS, bitrate, GOP, B-frames, SPS/PPS, RTSP transport, and server.
- Make the stream product-compatible for Android now and future iOS, starting from a conservative H.264 baseline.
- Confirm reconnect behavior without hardware reboot.
- Confirm whether decoder/render stutter is still reproducible after the Android reconnect-loop fix.
- Help the app/AI team collect real hardware frames for fire/smoke model validation.

Problems Rahil should keep watching for:

- delay growing from about 2-3 seconds to about 8 seconds if it appears again
- Android decoder/render stutter
- blocky/color stream corruption
- stale camera frames being analyzed by Android AI
- unstable person/wet/pothole/fire guidance caused by delayed, corrupted, or poorly exposed frames
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
- Android + future iOS codec/protocol compatibility
- physical camera orientation
- authentication behavior

## Definition Of Done

Rahil should only call hardware ready after:

- current hardware source/config backup is uploaded or linked
- as-built hardware details are documented in the repo
- hardware stream is documented as Android-compatible and future-iOS-compatible
- timestamp overlay/frame counter exists
- Mac `ffprobe` confirms expected codec/resolution/FPS
- Mac `ffplay` 15-minute test passes
- Android MITRA 15-minute test passes after the Android reconnect-loop fix is installed
- camera-to-phone delay does not grow
- no repeated visual corruption appears
- reconnect works without hardware reboot
- encoder and RTSP settings are documented
- H.264 product profile is documented or any deviation is explicitly justified
- evidence is sent back to MdShabazS/app team

## Evidence Rahil Should Return

Create and share:

```text
hardware_board.txt
camera_module.txt
firmware_version_or_commit.txt
hardware_source_backup_location.txt
hardware_source_tree.txt
startup_services.txt
dependency_versions.txt
wifi_config.txt
rtsp_server_config.txt
encoder_config.txt
ffprobe_output.txt
mac_ffplay_15min_notes.txt
android_15min_notes.txt
delay_screenshots_or_video_0_2_5_10_15min
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
- SPS/PPS behavior
- Android compatibility note
- future iOS compatibility note
- timestamp overlay proof
- current hardware code/config copy or artifact link
- 15-minute Mac result
- 15-minute Android result

## Hardware Code And Config Upload Rules

Rahil should preserve the hardware implementation so another engineer can rebuild or inspect it later.

Upload to this repo when practical:

```text
hardware/
  README.md
  as_built/
  firmware/
  configs/
  scripts/
  test-evidence/
```

If the hardware source is large or contains generated binaries, do not blindly commit everything. Instead:

- commit the source code, startup scripts, service files, config files, and dependency/version docs
- remove secrets before upload
- keep huge firmware images, model binaries, OS images, or vendor SDK zips outside Git
- add a Markdown file with the exact external artifact location, checksum, creation date, and owner

At minimum, the repo must contain enough hardware details for future Codex sessions to answer:

```text
What board is used?
What camera is used?
How does the stream start on boot?
Which command or service captures camera frames?
Which encoder creates the H.264 stream?
Which RTSP server serves rtsp://10.42.0.1:8554/stream?
What codec/profile/resolution/FPS/bitrate/GOP/B-frame settings are used?
How can Rahil or another engineer reproduce the same hardware stream from a clean board?
```

## Short Message MdShabazS Can Send Rahil

```text
Rahil, I added you as collaborator to this repo:
https://github.com/MdShabazS/MITRA-AndriodApp-V1

Open Codex on your laptop, give it this repo as reference, and first ask it to read:
docs/RAHIL_CODEX_START_GUIDE.md

Then paste:
"i m rahil lets start"

Codex should guide you step by step. The latest Android retest did not reproduce the earlier 8-second delay, so do not reset or recode the hardware from scratch first. The main goal now is to document and back up the current hardware implementation, confirm the exact RTSP stream format/settings, add timestamp/frame-counter proof, and tune only what the evidence shows is needed.
```
