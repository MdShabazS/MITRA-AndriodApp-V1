# MITRA Local Device QA Report - 2026-08-11

Scope: local Android app, MITRA hardware RTSP stream, local AI pipeline, foreground/background service behavior, build/install/unit tests, and evidence capture.

Cloud scope: skipped by request. WebSocket failures are recorded in logs but are not counted as failures in this QA pass.

Follow-up note: after the first report was written, the operator reported live-test observations that change the testing conclusion for voice, stream latency, and spoken hazard accuracy. Those observations are recorded in the relevant sections below.

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

Result: NEEDS FIX / RETEST

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

Issues found:

- At the 2-minute checkpoint, the visible video frame had blocky/color corruption even though the stream was still live and sampling.
- Logs showed decoder status/warnings from `c2.qti.avc.decoder`, with changing input/render FPS.
- Operator-observed latency drift: during the first 0-4 minutes the visual delay was about 2.5-2.9 seconds; after about 7 minutes the visual delay increased to about 8 seconds.
- The app's "last frame" value only proves that Android copied a recently rendered surface frame. It does not prove the camera frame itself is live, because a decoder/RTSP queue can keep rendering delayed frames.

Log evidence for latency/stutter:

- Early stream showed near-normal decoder/render FPS, for example around `19:30:21` the Qualcomm decoder logged about `inputFps=30`, `outputFps=30`, and `renderFps=30`.
- Later stream showed decoder/render stalls. Around `19:47:02`, OPPO reported `atlas_event_video_stutter` with `VideoInputFps=10`, `VideoOutputFps=10`, and `VideoRenderFps=2`.
- Around `19:47:07` to `19:47:10`, decoder logs included `inputFps=0`, `outputFps=0`, and `renderFps=0/13` in alternating seconds.
- OPPO's stutter upload still showed WiFi connected, RSSI about `-39 dBm`, CPU loading about `44`, and `CPULoadingHigh=false`, so the first suspect is not basic WiFi disconnect. The likely causes are decoder/render queue buildup, RTSP buffering, hardware encoder timestamp/GOP behavior, or a combination.

Evidence:

- `excerpt_hardware_rtsp.log`
- `screenshot_09_stability_2min.png`
- `logcat_full_local_qa.log`

Conclusion:

- RTSP connectivity and sampling are working.
- Stream freshness is not proven. The feed can stay connected while delayed frames accumulate.
- Stream visual quality and long-run latency must be fixed/retested with both Android player settings and hardware encoder/RTSP settings.

## Local AI / Hazard Engine

Result: NEEDS FIX / RETEST

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

Issues found from operator observation and logs:

- With the camera held constant on an indoor room view, the app still spoke inconsistent hazards such as pothole, wet, and pedestrian/caution.
- Later, with a person visible, the app did not consistently speak `Caution, person ahead`, while it continued speaking pothole or wet alerts.
- Logcat confirms local spoken hazard changes during the later stability period, including wet alerts around `19:47:00` and pothole alerts around `19:47:03` to `19:47:10`.

Probable causes in current app behavior:

- Current confidence thresholds are low for spoken safety alerts: fire/smoke `0.30`, wet `0.40`, pedestrian `0.30`, pothole `0.35`.
- The engine intentionally round-robins heavy features and reuses the last known detections for features that are not scheduled on the current frame.
- Spoken local hazards do not currently require temporal confirmation, such as 2-3 consecutive matching detections, before TTS.
- Local hazard detections have no distance value, so navigation speaks a generic caution when any local pothole/wet/fire/electric-pole detection is present.
- A delayed/corrupted stream makes this worse: the AI may be analyzing frames that are seconds behind the real camera view.
- Pothole should normally be skipped for indoor scenes. If pothole is spoken while viewing a room, the scene classifier likely flipped to outdoor for some frames or a cached pothole result remained usable for too long.

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

Result: NOT TESTED / INVALID

What was tested:

- Inserted log marker `VOICE_TEST_START mitra_time`.
- Codex attempted to use the Mac `say` command to speak `mitra time` while the phone was in background.
- The operator later confirmed that the Mac did not produce audible speech.

Observed:

- No `Heard:` log was produced for the test phrase.
- Logs showed repeated `STT error code: 7` and Google recognizer `NO_SPEECH_DETECTED`.
- The recognizer was cycling and receiving mic sessions, but the spoken phrase was not recognized.

Conclusion:

- Voice runtime appears alive.
- Actual live command execution was not tested because no valid speech input reached the phone.
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
| Visual stream latency / freshness | NEEDS FIX / RETEST |
| Local hazard engine execution | PASS |
| Local hazard speech accuracy | NEEDS FIX / RETEST |
| Local navigation/TTS decision loop | NEEDS FIX / RETEST |
| Foreground service survival in background | PASS |
| Accessibility service bound | PASS |
| Visual stream quality | NEEDS FIX / RETEST |
| Background stream frame availability | NEEDS DECISION / RETEST |
| Live voice command execution | NOT TESTED / INVALID |
| Cloud WebSocket/ACK/response | SKIPPED / PENDING |

## Recommended Fixes Before Next Full Test

1. Add a real stream freshness metric. Best hardware-side option: timestamp overlay or embedded source timestamp so Android can measure camera-to-screen latency directly.
2. Add a stream latency/stutter recovery path in Android. If decoder/render FPS collapses or visual delay crosses a threshold, reconnect the RTSP player and clear buffered frames. Partially implemented after this report with a 5-minute live-session refresh.
3. Tune Android RTSP player options for lower accumulated latency and compare TCP vs UDP over a 10-minute run. Partially implemented after this report by lowering LibVLC network/live/RTSP cache to 60 ms.
4. Ask hardware to confirm encoder settings: FPS, bitrate, GOP/keyframe interval, B-frames disabled/enabled, RTSP server buffering, and timestamp generation.
5. Stabilize spoken local hazards: require consecutive detections or short-window persistence before speaking wet, pothole, fire/smoke, and pedestrian alerts. Implemented after this report with fresh-evidence confirmation for local hazards.
6. Add a TTL for cached feature detections so old pothole/wet/person detections cannot be spoken after the scene has changed. Implemented after this report with a 2500 ms cache TTL plus NIGHT/INDOOR cache clearing.
7. Raise or split thresholds: keep model detection thresholds for logs, but use higher `spoken alert` thresholds for user-facing TTS. Superseded by the fresh-evidence confirmation rule so wet/pothole are not hidden by a hard speech threshold.
8. Revisit navigation priority so a fresh centered person detection is not hidden by stale/weak pothole or wet detections.

## Recommended Next Tests

1. Retest hardware stream latency for 10 minutes with a physical stopwatch or hardware timestamp overlay:
   - Expected: startup delay under 3 seconds and no growth past the chosen product limit.
   - Current observed failure: delay grew from about 2.5-2.9 seconds to about 8 seconds.
2. Retest a static indoor-room scene for false spoken hazards:
   - Expected: no pothole or wet alerts unless the model has repeated high-confidence evidence.
   - Current observed failure: pothole/wet/caution alerts occurred while the camera was constant.
3. Retest person visibility:
   - Expected: when a person is centered and visible, person/pedestrian caution should be consistent.
   - Current observed failure: person was present but person alert was inconsistent and sometimes replaced by pothole/wet alerts.
4. Repeat live voice command matrix with human speech near the phone:
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
5. Decide whether hardware stream frame capture must continue while app is in background.
6. Run one more 10-minute stability test with battery above 50%.
7. Keep cloud testing skipped until the user explicitly starts the cloud pass.
8. Run cloud WebSocket ACK/response test later as a separate QA pass.
