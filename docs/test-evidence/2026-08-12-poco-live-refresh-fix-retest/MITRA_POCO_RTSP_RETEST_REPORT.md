# MITRA Poco RTSP Retest Report - 2026-08-12

Owner: MdShabazS / Codex

Scope:

- Test MITRA Android app on the Poco phone after OPPO testing.
- Keep cloud testing pending.
- Use MITRA hardware WiFi and RTSP stream.
- Save logs, screenshots, battery, WiFi, and activity evidence locally.

## Device

| Item | Value |
|---|---|
| Device model | `25028PC03I` |
| Android version | `15` |
| Hardware WiFi | `MITRA_DEVICE` |
| Phone IP on MITRA WiFi | `10.42.0.179` |
| Test date | 2026-08-12 |

## Evidence Folders

| Folder | Purpose |
|---|---|
| `docs/test-evidence/2026-08-12-poco-hardware-15min-retest/` | Fresh-install 15-minute Poco hardware test before the final fix |
| `docs/test-evidence/2026-08-12-poco-live-refresh-fix-retest/` | Patched-build retest focused on the 5-minute live-refresh threshold |

Raw logcat files and screenshots were saved in those folders on the test machine. Raw `.log` files are intentionally not committed because `.gitignore` excludes generated logs.

## Fresh-Install 15-Minute Result

Status: FAIL for product RTSP stability on the pre-fix build.

What worked:

- APK installed successfully after the user uninstalled the app.
- Poco authorized over ADB.
- Poco connected to `MITRA_DEVICE`.
- The app opened the hardware stream at `640x480`.
- Initial live stream showed about `2.2` sampled FPS with fresh frames.
- End checkpoint recovered to live with fresh frames.

Problem reproduced:

- At the 5-minute and 10-minute checkpoints the overlay showed:
  - `MITRA hardware: reconnecting:live-refresh:vlc-tcp`
  - `Sample FPS: 0.0`
  - `Last frame: never`
- Logcat showed the live-refresh watchdog scheduling reconnect repeatedly instead of performing one controlled refresh.

Pre-fix counts from `poco_15min_counts.txt`:

| Metric | Count |
|---|---:|
| `scheduling reconnect (live-refresh)` | 693 |
| `watchdog: refreshing live RTSP session` | 693 |
| `PixelCopy failed` | 3 |
| Local nav TTS spoken | 109 |
| Low-light TTS spoken | 20 |
| Walk TTS spoken | 62 |

Interpretation:

- This was an Android app-side reconnect-state bug, not proof that Rahil must recode the hardware.
- The scheduled live-refresh reconnect could be cleared by existing playback/frame events before the restart actually executed. The watchdog then saw the same 5-minute refresh condition every 800 ms and kept scheduling refreshes.

## Android Fix

File changed:

- `app/src/main/java/com/unique/visionmate/RtspFrameSource.kt`

Behavior changed:

- A scheduled reconnect now records whether the restart has actually started.
- `live-refresh` reconnects cannot be cleared by old frame captures before the restart executes.
- Playback/frame events from the restarted player still clear reconnect state normally.
- Build tag changed to `rtsp-libvlc-live-refresh-pending-guard` so logcat proves the patched build is installed.

## Focused Fixed-Build Retest

Status: PASS for the specific Poco live-refresh reconnect-loop fix.

Retest method:

- Installed patched debug APK.
- Cleared logcat.
- Started a separate fix-retest capture.
- Opened the hardware stream.
- Ran a 7-minute live-stream window with checkpoints at:
  - 2 minutes
  - 5.5 minutes
  - 7 minutes

Fixed-build screenshots showed:

| Checkpoint | Overlay result |
|---|---|
| 2 min | `live`, `Sample FPS: 2.2`, `Last frame: 14ms ago` |
| 5.5 min | `live`, `Sample FPS: 2.2`, `Last frame: 355ms ago` |
| 7 min | `live`, `Sample FPS: 2.2`, `Last frame: 366ms ago` |

Fixed-build counts from `poco_fix_retest_counts.txt`:

| Metric | Count |
|---|---:|
| `scheduling reconnect (live-refresh)` | 1 |
| `watchdog: refreshing live RTSP session` | 1 |
| `watchdog: no captured frame` | 1 |
| `PixelCopy failed` | 5 |
| Local nav TTS spoken | 63 |
| Low-light TTS spoken | 9 |
| Walk TTS spoken | 37 |

Key log proof:

```text
frame source started ... [build rtsp-libvlc-live-refresh-pending-guard]
watchdog: refreshing live RTSP session to clear possible decoder/RTSP backlog
scheduling reconnect (live-refresh)
LibVLC playing transport=vlc-auto/udp
```

Interpretation:

- The 800 ms reconnect storm is fixed on Poco for the tested path.
- One live-refresh at the 5-minute point is expected.
- After that refresh, the stream remained live and frame sampling continued.

## Accessibility / Background Observation

- During the patched retest, the app opened the MITRA accessibility settings once.
- The MITRA accessibility service switch was already ON.
- Pressing Back from accessibility settings landed on the home screen, which confirms a background/app-switch scenario was exercised.
- The app could be relaunched and returned to the stream path.

This means accessibility was not disabled on Poco during this run, but the setup flow can still send the user to accessibility settings and should be kept in the compatibility checklist.

## Remaining Testing Pending

Cloud remains intentionally pending.

Still pending before product release:

- Repeat a full 15-minute Poco run using the patched APK, not only the 7-minute focused refresh retest.
- Run the full live voice command matrix on Poco:
  - wake word `mitra`
  - time/battery/status commands
  - start/stop MITRA
  - open app
  - call/contact command where safe
  - background command after pressing Home
- Test phone-camera fallback on Poco with MITRA hardware unavailable.
- Test notification access and WhatsApp/send accessibility flows if needed.
- Validate local fire/smoke, wet, pothole, and person alerts with controlled real scenes.
- Add hardware timestamp/frame-counter proof for exact latency measurement.
- Complete cross-OEM matrix: Samsung, Vivo/iQOO, Pixel/AOSP-like, OnePlus.

## Hardware Decision

Do not reset or recode the hardware from scratch based on this Poco result.

Rahil should still:

- Document the current stream format and code/config.
- Add timestamp or frame-counter proof.
- Confirm encoder/RTSP settings.
- Tune hardware only if fresh evidence after the Android fix shows hardware-side delay growth, corruption, reconnect failure, or timestamp mismatch.
