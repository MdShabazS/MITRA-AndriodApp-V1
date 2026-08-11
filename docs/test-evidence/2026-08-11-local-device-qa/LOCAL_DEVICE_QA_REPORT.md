# MITRA Local Device QA Report - 2026-08-11

Scope: local Android app, MITRA hardware RTSP stream, local AI pipeline, foreground/background service behavior, build/install/unit tests, and evidence capture.

Cloud scope: skipped by request. WebSocket failures are recorded in logs but are not counted as failures in this QA pass.

## Test Build

Repository commit:

```text
359cb94 Add MITRA master handoff document
```

Device:

```text
OPPO CPH2729 / CPH2729IN
ADB serial: 92edef11
Android app package: com.unique.visionmate
```

Evidence files:

- `adb_devices.txt`
- `device_getprop.txt`
- `git_commit.txt`
- `package_before_install.txt`
- `package_after_install.txt`
- `gradle_assembleDebug.log`
- `gradle_unit_tests.log`
- `adb_install.log`
- `logcat_full_local_qa.log`

## Automated Checks

| Check | Result | Evidence |
|---|---|---|
| `:app:assembleDebug` | PASS | `gradle_assembleDebug.log` shows `BUILD SUCCESSFUL` |
| APK install | PASS | `adb_install.log` shows `Success` |
| `:engine:testDebugUnitTest` | PASS | `gradle_unit_tests.log` shows `BUILD SUCCESSFUL` |
| `:app:testDebugUnitTest` | PASS | `gradle_unit_tests.log` shows `BUILD SUCCESSFUL` |

## Hardware WiFi

Result: PASS

Evidence:

- `wifi_stream_live.txt`

Observed connection:

```text
SSID: MITRA_DEVICE
BSSID: 88:a2:9e:89:9a:48
Phone IP: 10.42.0.168/24
Gateway/DNS: 10.42.0.1
Frequency: 2462 MHz
Link speed: 72 Mbps
RSSI around stream start: about -35 dBm to -41 dBm
```

## Hardware RTSP Stream

Result: PARTIAL PASS

What passed:

- App opened `VideoActivity`.
- Hardware stream became live.
- Frame sampling worked at about 2.2 FPS.
- PixelCopy/sample size was 640 x 480.
- The stream stayed alive through the 10-minute stability window.
- App process stayed alive.

Key screenshots:

- `screenshot_03_stream_after_wait.png`: stream live, sampled 91, 2.2 FPS, 640 x 480, last frame 43 ms ago.
- `screenshot_09_stability_2min.png`: stream live after 2 minutes, sampled 409, 2.2 FPS, 640 x 480, last frame 350 ms ago.
- `screenshot_10_stability_10min.png`: stream live after 10 minutes, sampled 1579, 2.2 FPS, 640 x 480, last frame 276 ms ago.

Issue found:

- At the 2-minute checkpoint, the visible video frame had blocky/color corruption even though the stream was still live and sampling.
- Logs showed decoder status/warnings from `c2.qti.avc.decoder`, with changing input/render FPS.

Evidence:

- `excerpt_hardware_rtsp.log`
- `screenshot_09_stability_2min.png`

Conclusion:

- RTSP connectivity and sampling are working.
- Stream visual quality is not fully clean and should be retested with hardware/encoder settings.

## Local AI / Hazard Engine

Result: PASS

What passed:

- `EngineBridge` submitted frames continuously.
- `HAZARD dayNightGate` logs were produced.
- `SCENE frame=` logs were produced.
- Local navigation/TTS decision logs were produced.
- No MITRA crash or ANR was found in the MITRA-filtered crash scan.

Observed counts in full log:

```text
EngineBridge submitted markers: 97
dayNightGate logs: 885
SCENE frame logs: 258
```

Example behavior observed:

- DAY and NIGHT day/night results were logged.
- Indoor scene classifications were logged.
- Local-only navigation TTS was spoken or throttled based on cooldown rules.
- Example local guidance included:
  - `Continue straight. The indoor path ahead is clear.`
  - `Low light detected. Please wait; navigation guidance is limited.`
  - `Caution, person ahead.`
  - `Fire or smoke detected ahead. Stop and move away carefully.`

Evidence:

- `excerpt_local_ai.log`
- `logcat_full_local_qa.log`

## Background / Foreground Service

Result: PASS for service survival; PARTIAL for stream frames in background

What passed:

- After pressing Home, the MITRA process remained alive.
- `BackgroundService` remained a foreground service.
- `AutoSendAccessibilityService` was bound.
- Foreground service notification was active.

Evidence:

- `pid_after_home_background.txt` contains process id `13515`.
- `services_after_home_background.txt` shows:
  - `com.unique.visionmate/.BackgroundService`
  - `isForeground=true`
  - `foregroundId=1`
  - `com.unique.visionmate/.AutoSendAccessibilityService`

Issue / limitation observed:

- After pressing Home, `VOICE_BG` began logging `No video frame available`.
- `EngineBridge` later logged `frame provider returned null (texture not ready or playback paused)`.

Conclusion:

- Background voice/service layer survives.
- Hardware stream frame availability while the app is backgrounded is not guaranteed and needs a product decision: either expected behavior, or a bug if true background camera/stream processing is required.

## Voice Recognition / Commands

Result: INCONCLUSIVE

What was tested:

- Inserted log marker `VOICE_TEST_START mitra_time`.
- Codex used the Mac `say` command to speak `mitra time` while the phone was in background.

Observed:

- No `Heard:` log was produced for the test phrase.
- Logs showed repeated `STT error code: 7` and Google recognizer `NO_SPEECH_DETECTED`.
- The recognizer was cycling and receiving mic sessions, but the spoken phrase was not recognized.

Conclusion:

- Voice runtime appears alive.
- Actual live command execution was not proven in this pass.
- Voice command matrix still needs a human-spoken retest in a quiet room with the phone close to the speaker/user.

Evidence:

- `excerpt_voice_background.log`
- `logcat_full_local_qa.log`

## Cloud

Result: SKIPPED

By request, cloud testing was not part of this QA pass.

Observed but ignored for this pass:

- `WebSocket failure: Failed to connect to /151.185.32.13:8765`
- `Frame ack timeout`
- `Scheduling WebSocket reconnect`

Evidence:

- `excerpt_cloud_skipped.log`

Cloud remains pending for a later dedicated test.

## Battery / Thermal Condition

Evidence:

- `battery_stability_10min.txt`

Observed at end of stability window:

```text
Battery level: 19%
USB powered: true
PhoneTemp: 360
Battery temperature: 348
```

Note:

- The test ran with low battery. Repeat one final release QA run with higher battery to remove battery-saving behavior as a variable.

## Pass / Pending Summary

| Area | Status |
|---|---|
| ADB device detection | PASS |
| Build | PASS |
| Install | PASS |
| Unit tests | PASS |
| MITRA WiFi connection | PASS |
| Hardware RTSP opens | PASS |
| Frame sampling | PASS |
| 10-minute stream survival | PASS |
| Local hazard engine | PASS |
| Local navigation/TTS decision loop | PASS |
| Foreground service survival in background | PASS |
| Accessibility service bound | PASS |
| Visual stream quality | NEEDS FIX / RETEST |
| Background stream frame availability | NEEDS DECISION / RETEST |
| Live voice command execution | INCONCLUSIVE |
| Cloud WebSocket/ACK/response | SKIPPED / PENDING |

## Recommended Next Tests

1. Retest hardware stream visual quality while changing hardware camera/encoder/RTSP settings.
2. Repeat live voice command matrix with human speech near the phone:
   - `mitra time`
   - `mitra date`
   - `mitra battery`
   - `mitra open youtube`
   - `mitra open whatsapp`
   - `mitra go home`
   - `mitra back`
   - `mitra torch on`
   - `mitra torch off`
   - `mitra volume up`
   - `mitra volume down`
   - `mitra read text`
   - `mitra take photo`
   - `mitra start`
   - `mitra stop listening`
3. Decide whether hardware stream frame capture must continue while app is in background.
4. Run one more 10-minute stability test with battery above 50%.
5. Run cloud WebSocket ACK/response test later as a separate QA pass.
