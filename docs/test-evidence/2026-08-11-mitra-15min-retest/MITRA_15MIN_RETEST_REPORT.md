# MITRA 15-Minute Hardware Stream Retest

Date: 2026-08-11
Owner: MdShabazS / Codex
Device: physical Android phone connected to MITRA hardware WiFi
Cloud path: skipped for this run

## Test Goal

Verify the MITRA hardware RTSP stream over a fixed-camera 15-minute run and collect proof for:

- Android live-feed freshness over time.
- Whether the previous long-run delay growth reproduces.
- Decoder/RTSP stability.
- Local navigation speech behavior while the camera is fixed and mostly indoor/dim.

## Evidence Captured

Raw evidence folder:

```text
docs/test-evidence/2026-08-11-mitra-15min-retest/
```

Key evidence files:

- `logcat_full_15min_retest.log`
- `excerpt_rtsp_stability.log`
- `excerpt_local_ai_tts.log`
- `excerpt_test_markers.log`
- `screenshot_2min.png`
- `screenshot_5min.png`
- `screenshot_10min.png`
- `screenshot_15min.png`
- `screenshot_end.png`
- `battery_start.txt`, `battery_2min.txt`, `battery_5min.txt`, `battery_10min.txt`, `battery_15min.txt`, `battery_end.txt`
- `wifi_start.txt`, `wifi_2min.txt`, `wifi_5min.txt`, `wifi_10min.txt`, `wifi_15min.txt`, `wifi_end.txt`

## Timeline

| Time | Evidence | Result |
|---|---|---|
| Launch | `screenshot_02_after_second_tap.png` | Hardware stream live, `Sample FPS: 2.2`, `Last frame: 184ms ago` |
| 2 min | `screenshot_2min.png` | `Sampled: 420`, `Sample FPS: 2.2`, `Last frame: 68ms ago` |
| 5 min | `screenshot_5min.png` | Refresh occurred, `Sampled: 1`, `Last frame: 127ms ago` |
| 10 min | `screenshot_10min.png` | `Sampled: 486`, `Sample FPS: 2.2`, `Last frame: 147ms ago` |
| 15 min | `screenshot_15min.png` | `Sampled: 31`, `Sample FPS: 2.2`, `Last frame: 211ms ago` |
| End | `screenshot_end.png` | `Sampled: 439`, `Sample FPS: 2.2`, `Last frame: 56ms ago` |

## Findings

### 1. Android preview freshness improved

The app did not show the previous 8-second UI-frame age symptom during this run. Every captured checkpoint showed a fresh displayed frame age under 250 ms.

This does not prove true camera-to-phone glass latency because the hardware stream does not yet include a source timestamp or timestamp overlay. The `Last frame` value proves only that Android is sampling a fresh decoded frame from the currently displayed stream.

### 2. RTSP refresh watchdog had a reconnect-loop bug

The log captured 237 repeated lines like:

```text
RtspFrameSource: scheduling reconnect (live-refresh)
```

Root cause found in `RtspFrameSource`: the reconnect runnable cleared `reconnectScheduled` before LibVLC had actually reached `Playing` or produced a new frame, so the watchdog could schedule another refresh every 800 ms.

Fix applied after the test:

- Keep reconnect state active until LibVLC plays, vout/first frame arrives, or a bounded reconnect timeout fires.
- Add a reconnect attempt timeout fallback.
- Update build tag to `rtsp-libvlc-low-latency-refresh-guard`.

This fix needs a fresh phone+hardware retest.

### 3. Decoder stutter still appeared near the end

The log contains two OPPO/Atlas decoder stutter events:

```text
23:42:11 atlas_event_video_stutter
23:43:01 atlas_event_video_stutter
```

These happened near/after the 15-minute marker while the app still showed fresh frame counters. This means the app display remained live at checkpoints, but decoder stability still needs another run after the reconnect-loop fix.

### 4. Local fire/smoke speech is not product-ready

The run captured 42 `Local-only nav TTS spoken` lines for:

```text
Fire or smoke detected ahead. Stop and move away carefully.
```

The camera view did not contain fire or smoke. This is not acceptable as product behavior.

Important: this should not be hidden by simply disabling labels in navigation. The correct product-level fix is to validate or recalibrate the local fire/smoke model against real MITRA hardware frames and add a stronger verification signal before user-facing emergency speech. Until that is done, local fire/smoke spoken guidance must remain a known blocker.

### 5. Battery and thermal context

The phone stayed at 4% battery while USB-powered during the test.

Battery temperature rose from about 33.7 C at start to about 37.2 C at end:

```text
start: level 4, temperature 337
2 min: level 4, temperature 342
5 min: level 4, temperature 347
10 min: level 4, temperature 357
15 min: level 4, temperature 365
end: level 4, temperature 372
```

This low-battery/charging state should be treated as a stress condition. A production test should be repeated with the phone above 30% battery and with hardware temperature noted.

## Result

Pass:

- App remained on the hardware live screen.
- Android-side displayed frame freshness stayed under 250 ms at recorded checkpoints.
- Evidence was captured at 2, 5, 10, and 15 minutes.
- RTSP repeated-refresh bug was identified and fixed in code.

Fail / Blocker:

- Local fire/smoke TTS produced repeated false emergency speech.
- Decoder stutter events still appeared near the end.
- The reconnect-loop fix has not yet been retested on phone+hardware.
- True glass-to-glass latency cannot be measured precisely until hardware provides a visible timestamp overlay or embedded frame timestamp.

## Required Next Test

Repeat the same 15-minute test after installing the reconnect-loop fix:

- Phone battery above 30%.
- Hardware connected to `MITRA_DEVICE`.
- Camera fixed.
- Finger-near-camera latency check at 2, 5, 10, and 15 minutes.
- Record whether the false fire/smoke speech still occurs.
- Check logcat for:
  - `scheduling reconnect (live-refresh)`
  - `LibVLC opening`
  - `LibVLC playing`
  - `atlas_event_video_stutter`
  - `Local-only nav TTS spoken`

## Pending

- Cloud/WebSocket path testing remains skipped.
- Hardware timestamp overlay or frame timestamp contract remains pending.
- Local fire/smoke model validation with real hardware frames remains pending.
