# MITRA Hardware As-Built Capture Template

Audience: Rahil / Hardware Engineering

Purpose:

- Capture the real current MITRA hardware implementation.
- Give the Android, AI, and future Codex sessions a hardware source of truth.
- Preserve enough code/configuration detail so another engineer can reproduce the stream.
- Confirm the hardware stream remains compatible with Android now and future iOS.

Fill this file after connecting to the hardware. Commit the completed copy to the repo as:

```text
hardware/as_built/HARDWARE_AS_BUILT_<date>.md
```

Use `YYYY-MM-DD` for `<date>`.

## 1. Hardware Identity

| Item | Value |
|---|---|
| Board/model | TODO |
| CPU/SoC | TODO |
| RAM/storage | TODO |
| OS/image name | TODO |
| OS/image version | TODO |
| Kernel version | TODO |
| Firmware/software commit or version | TODO |
| Power source during test | TODO |
| Cooling/thermal notes | TODO |

## 2. Camera Module

| Item | Value |
|---|---|
| Camera module/model | TODO |
| Sensor model | TODO |
| Lens/FOV | TODO |
| Interface | TODO |
| Driver/capture stack | TODO |
| Native resolution | TODO |
| Streamed resolution | TODO |
| Orientation/rotation | TODO |
| Exposure mode | TODO |
| White balance mode | TODO |
| Low-light behavior notes | TODO |

## 3. WiFi/AP Configuration

| Item | Required Android value | Actual hardware value |
|---|---|---|
| SSID | `MITRA_DEVICE` | TODO |
| Hardware IP | `10.42.0.1` | TODO |
| DHCP range | `10.42.0.x` | TODO |
| Band/channel | Documented | TODO |
| Auth/password behavior | Must be documented | TODO |
| Startup service/config file | Must be documented | TODO |

Do not include private passwords or secrets in this repo. If a secret exists, write where it is stored and who owns it.

## 4. Stream Format

| Item | Value |
|---|---|
| RTSP URL | `rtsp://10.42.0.1:8554/stream` |
| RTSP server/software | TODO |
| RTSP server version | TODO |
| Transport supported | TCP / UDP / both / TODO |
| Codec | TODO |
| Codec profile | TODO |
| Resolution | TODO |
| FPS | TODO |
| Bitrate | TODO |
| GOP/keyframe interval | TODO |
| B-frames | TODO |
| SPS/PPS behavior | TODO |
| Audio | disabled / enabled / TODO |
| Pixel format before encoder | TODO |
| Timestamp source | TODO |
| Timestamp overlay/frame counter | yes / no / TODO |
| Android compatibility result | TODO |
| Future iOS compatibility notes | TODO |

MITRA product target unless MdShabazS approves a deviation:

| Setting | Target |
|---|---|
| Codec baseline | H.264 |
| First target resolution | 640 x 480 |
| First target FPS | 15 FPS |
| GOP/keyframe interval | 1 second |
| B-frames | disabled |
| SPS/PPS | present at stream start and repeated with keyframes when possible |
| Bitrate | capped/stable |
| Queues | bounded; drop old frames if the client falls behind |
| H.265 | optional future experiment only, not the product baseline |

## 5. Stream Startup Path

Document exactly how the stream starts.

```text
Boot
  -> TODO service/script
  -> TODO camera command
  -> TODO encoder command/settings
  -> TODO RTSP server command/settings
  -> rtsp://10.42.0.1:8554/stream
```

Files/services involved:

```text
TODO
```

Main command or pipeline:

```bash
TODO
```

## 6. Hardware Code And Config Backup

Create a folder in the repo when practical:

```text
hardware/
  README.md
  as_built/
  firmware/
  configs/
  scripts/
  test-evidence/
```

Record what was copied:

| Item | Repo path or artifact link | Notes |
|---|---|---|
| Firmware/source code | TODO | TODO |
| Startup scripts/services | TODO | TODO |
| WiFi/AP config | TODO | TODO |
| Camera config | TODO | TODO |
| Encoder config | TODO | TODO |
| RTSP server config | TODO | TODO |
| Dependency/version list | TODO | TODO |
| External large artifact | TODO | checksum/date/owner |

Before upload:

- Remove passwords, tokens, SSH keys, private certificates, and personal network secrets.
- Do not commit huge OS images, firmware binaries, vendor SDK zips, or generated build folders unless MdShabazS explicitly approves.
- If a large artifact is needed, store it outside Git and document the link, checksum, date, and owner here.

## 7. Test Evidence

### ffprobe

Command:

```bash
ffprobe -hide_banner -show_streams -show_format rtsp://10.42.0.1:8554/stream
```

Paste output or save to:

```text
hardware/test-evidence/<date>/ffprobe_output.txt
```

Summary:

| Metric | Value |
|---|---|
| codec_name | TODO |
| profile | TODO |
| width x height | TODO |
| avg_frame_rate | TODO |
| r_frame_rate | TODO |
| bit_rate | TODO |
| time_base | TODO |

### ffplay / VLC 15-Minute Test

| Time | Observed delay | Corruption? | Notes |
|---|---:|---|---|
| 0 min | TODO | TODO | TODO |
| 2 min | TODO | TODO | TODO |
| 5 min | TODO | TODO | TODO |
| 10 min | TODO | TODO | TODO |
| 15 min | TODO | TODO | TODO |

### Android MITRA 15-Minute Test

| Time | Android `Last frame` | Finger/timestamp delay | Corruption? | Notes |
|---|---:|---:|---|---|
| 0 min | TODO | TODO | TODO | TODO |
| 2 min | TODO | TODO | TODO | TODO |
| 5 min | TODO | TODO | TODO | TODO |
| 10 min | TODO | TODO | TODO | TODO |
| 15 min | TODO | TODO | TODO | TODO |

## 8. Pass / Fail

| Gate | Pass? | Evidence |
|---|---|---|
| Android contract preserved | TODO | TODO |
| Timestamp/frame counter available | TODO | TODO |
| 15-minute Mac stream stable | TODO | TODO |
| 15-minute Android stream stable | TODO | TODO |
| Delay does not grow | TODO | TODO |
| Reconnect works without reboot | TODO | TODO |
| Exact stream format documented | TODO | TODO |
| Hardware code/config backed up | TODO | TODO |
| Android compatibility documented | TODO | TODO |
| Future iOS compatibility documented | TODO | TODO |
| H.264 product profile met or deviation justified | TODO | TODO |

## 9. Known Issues

List every remaining hardware issue, even if small:

```text
TODO
```

## 10. Change Log

Every hardware change after this capture must be recorded here.

| Date | Change | Reason | Test result | Commit/artifact |
|---|---|---|---|---|
| TODO | Initial as-built capture | Documentation baseline | TODO | TODO |
