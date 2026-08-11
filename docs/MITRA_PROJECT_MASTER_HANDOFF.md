# MITRA Android App Master Handoff

Last updated: 2026-08-11

Owner repo: `https://github.com/MdShabazS/MITRA-AndriodApp-V1`

App package: `com.unique.visionmate`

This is the first document to give to a new teammate or a new AI chat before continuing MITRA Android work. It captures the app architecture, hardware contract, AI pipeline, completed work, test evidence, and remaining tasks.

## 1. Current Position

MITRA is an Android assistive-vision app for blind and low-vision users. The app connects to the MITRA hardware camera over the hardware WiFi network, displays the live RTSP feed, runs local hazard inference, sends frames and metadata to the cloud backend, and supports voice commands in foreground and background.

The current active development repo is the personal GitHub repository:

```text
https://github.com/MdShabazS/MITRA-AndriodApp-V1
```

The organization repository `ihelprobotics/AndroidApp` must be treated as an upstream review target only. Do not push work directly to the organization repo from this workspace.

Local remotes at the time this handoff was written:

```text
origin   https://github.com/MdShabazS/MITRA-AndriodApp-V1.git
upstream https://github.com/ihelprobotics/AndroidApp.git
upstream push is disabled locally
```

## 2. Important AI Instructions

Before doing deep code work, read:

- `AGENTS.md`
- `README.md`
- `WORKFLOW.md`
- `COMMANDS.md`
- `docs/WORK_LOG.md`
- `docs/HARDWARE_INTEGRATION.md`
- `docs/HARDWARE_STREAM_CONTRACT_AND_QA.md`
- `docs/DOCUMENTATION_STANDARDS.md`

`AGENTS.md` asks future AI agents to use the generated knowledge graph first for structure and call graph questions. At the time of this handoff, `graphify-out/` is not present in the checked workspace. Regenerate the Android knowledge graph before any large architecture or symbol-level task.

Do not rely only on older notes. Some historical notes say the app is a single Gradle module or has no ML runtime. The current repository has an `app` module and an `engine` module, and the current README/WORKFLOW/source should be treated as the truth.

Every meaningful code, model, hardware-contract, workflow, or AI behavior change must update Markdown documentation in the same branch. At minimum, add an entry to `docs/WORK_LOG.md`.

## 3. Product Summary

MITRA has three main jobs:

1. Connect to the wearable or mounted MITRA hardware camera.
2. Understand the live visual environment with local AI and cloud AI.
3. Help the user through speech, navigation guidance, commands, calls, messages, and app automation.

The phone app is the main control and intelligence layer. The hardware provides the camera stream over WiFi.

Primary user-facing modes:

- MITRA hardware mode: connect to `MITRA_DEVICE`, open RTSP stream, run inference on hardware camera frames.
- Phone camera fallback mode: use the phone back camera when hardware is unavailable.
- Voice assistant mode: listen for commands, read messages, open apps, call contacts, navigate, OCR text, and control phone actions.
- Offline test mode: run hazard inference on a local test video for development and QA.

## 4. Repository Layout

Important top-level files and folders:

| Path | Purpose |
|---|---|
| `app/` | Android UI, permissions, WiFi setup, RTSP stream, camera fallback, voice service, cloud streaming, accessibility, notifications |
| `engine/` | Local hazard inference pipeline and feature runners |
| `app/src/main/assets/models/` | Bundled TFLite model files and model manifest used by the app |
| `README.md` | Repo overview and build notes |
| `WORKFLOW.md` | End-to-end app behavior |
| `COMMANDS.md` | Voice command reference |
| `STREAMING_METADATA_SCHEMA.md` | Current metadata examples and streaming payload notes |
| `AGENTS.md` | AI working rules for this repo |
| `docs/` | Team docs, QA evidence, hardware contract, release notes, work log |

## 5. Main Android Components

| Component | Role |
|---|---|
| `MainActivity` | Home screen, onboarding, permission checks, voice toggle, START action, hardware search, hardware-vs-camera routing |
| `BackgroundService` | Foreground service for wake word, speech recognition, TTS, command handling, WiFi hold, cloud streaming, and call-state behavior |
| `VideoActivity` | MITRA hardware RTSP stream screen; attaches hazard engine to stream frames |
| `RtspFrameSource` | LibVLC RTSP decoder path, transport fallback, first-frame watchdog, frame capture |
| `VideoFrameCache` | Shared frame cache used by engine and cloud sender |
| `CameraActivity` | Phone back-camera fallback and camera QA path |
| `OfflineTestActivity` | Development and QA path for running the engine on local video |
| `EngineBridge` | Single app-to-engine integration point |
| `AutoSendAccessibilityService` | Background app launches, WhatsApp send tap, camera shutter, Home/Back/system actions |
| `MitraNotificationListener` | Reads WhatsApp/SMS notifications aloud and stores reply action |
| `VoiceCommandSelector` | Chooses the best speech-recognition candidate and enforces wake-word matching |
| `engine` module | Day/night gate, scene classification, model-backed hazard features, OCR integration, result packaging |

## 6. End-To-End App Flow

### 6.1 App Startup

1. User opens MITRA.
2. `MainActivity` checks required setup and permissions.
3. Voice is enabled by default on the home screen.
4. User can say a command directly on the home screen.
5. Hardware search does not start until the user taps START or says `start mitra`.

Required permissions and settings:

- Microphone
- Camera
- Contacts
- Phone
- Accessibility service
- Notification access
- Display over other apps
- Battery optimization exemption where needed

### 6.2 Start MITRA Hardware Flow

Triggered by the START button or the voice command `start mitra`.

1. App checks whether the phone is already connected to MITRA WiFi.
2. If already connected, it skips a long scan and opens the stream flow.
3. If not connected, it searches for the MITRA WiFi SSID.
4. If hardware is found, app asks for password when required and connects.
5. App opens `VideoActivity`.
6. `VideoActivity` starts RTSP playback and frame capture.
7. Frames go to local inference and cloud upload.
8. If hardware is unavailable, app falls back to `CameraActivity`.

### 6.3 Hardware RTSP Stream Flow

Current hardware stream URL:

```text
rtsp://10.42.0.1:8554/stream
```

The app decodes the stream with LibVLC. Audio is disabled. Captured frames are sampled from the displayed stream using PixelCopy, then pushed into `VideoFrameCache`.

Current stream behavior:

- First transport attempt: last successful transport saved in app memory.
- If no previous transport is saved, first attempt is RTSP over TCP.
- Fallback alternates to LibVLC automatic/UDP behavior.
- TCP first-frame timeout: about 3200 ms.
- UDP/automatic first-frame timeout: about 2800 ms.
- If LibVLC reports video output before PixelCopy captures a frame, PixelCopy receives an additional grace window.
- Live stall timeout: about 4000 ms.
- Reconnect delay: about 450 ms.
- Frame capture target: 640 x 480 ARGB_8888 before JPEG/cloud packaging.
- Local frame sample interval: about 450 ms, around 2.2 FPS capture opportunity.
- Cloud upload target: 1 FPS.
- Cloud JPEG quality: 70.

### 6.4 Phone Camera Fallback Flow

`CameraActivity` is the phone back-camera path. It is also used for local QA.

Important current behavior:

- Camera QA no longer starts the voice runtime automatically.
- Camera QA sends `ACTION_STOP_MITRA_RUNTIME` and clears MITRA runtime active state when opened.
- This prevents repeated microphone start/stop sounds while testing camera inference.
- Main app voice behavior remains separate.

### 6.5 Local Hazard Inference Flow

The current documented pipeline is:

```text
frame
  -> DayNightGate
    -> if NIGHT: skip model-backed features and publish NIGHT result
    -> if DAY:
      -> SceneClassifier
      -> FireSmoke
      -> WetDry
      -> OCR
      -> if OUTDOOR:
        -> Pothole
        -> ElectricPole
        -> Pedestrian
      -> HazardFrameResult
```

Main output concept:

```kotlin
data class HazardFrameResult(
    val frameId: Long,
    val tsMs: Long,
    val dayNight: DayNight,
    val sceneType: SceneType?,
    val executedFeatures: Set<Feature>,
    val skippedFeatures: Map<Feature, SkipReason>,
    val detectionsByFeature: Map<Feature, List<Detection>>,
    val latenciesMs: Map<Feature, Long>,
    val skipReason: SkipReason?
)
```

Local model and inference notes:

- Day/night is algorithmic luminance gating.
- OCR uses ML Kit text recognition.
- Other hazard features use bundled local TFLite models.
- Model metadata is controlled by the assets model manifest.
- Missing or disabled model specs should disable features instead of crashing the app.

### 6.6 Cloud Streaming Flow

The Android app packages frames and metadata over WebSocket with MessagePack. Current docs show two related shapes, and this is still a pending cleanup item.

Known current hardware stream packaging from `docs/HARDWARE_STREAM_CONTRACT_AND_QA.md`:

- Protocol: MessagePack
- Frame image: JPEG
- JPEG quality: 70
- Target send cadence: 1 FPS
- Example top-level fields:
  - `protocol`
  - `device_id`
  - `session_id`
  - `timestamp`
  - `seq`
  - `frame_index`
  - `frame`
  - `meta`
  - `sensor`
  - `mobile_feature_outputs`

Common metadata fields:

- width
- height
- fps
- format
- device_type
- source
- scene_type
- day_night

Important pending item:

`STREAMING_METADATA_SCHEMA.md`, `BackgroundService`, and `CameraActivity` have not yet been fully canonicalized into one Android-to-cloud MessagePack contract. Do not consider the cloud contract final until a decoded payload sample is verified with backend.

### 6.7 Voice And Background Flow

Canonical wake word:

```text
mitra
```

Accepted wake-word variants:

- `mitra`
- `mi tra`
- `mithra`

Near misses such as `mathura`, `myntra`, and `mitrata` are intentionally rejected.

Foreground behavior:

- On MITRA home screen, commands can be spoken directly.

Background behavior:

- When another app is in front, prefix commands with `mitra`.
- Example: `mitra open whatsapp`.

Important voice command areas:

- Open apps
- Call contacts
- WhatsApp message send
- Notification reply
- Google Maps navigation
- YouTube playback
- OCR read text
- Take picture
- Torch on/off
- Volume controls
- Lock, screenshot, recent apps, notifications, quick settings
- Time, date, battery
- Sleep and repeat

See `COMMANDS.md` for the full command list.

## 7. Hardware Contract

The hardware engineer should treat these as the current Android expectations.

| Item | Current Value |
|---|---|
| Hardware WiFi SSID | `MITRA_DEVICE` |
| Hardware IP | `10.42.0.1` |
| RTSP URL | `rtsp://10.42.0.1:8554/stream` |
| RTSP port | `8554` |
| RTSP path | `/stream` |
| Preferred repeat-launch behavior | Try last successful transport first |
| Fallback behavior | Try alternate RTSP transport if first frame fails |
| App decoder | LibVLC |
| Audio | Disabled by Android client |
| PixelCopy frame size observed | 640 x 480 |
| Pixel format after capture | ARGB_8888 |
| Cloud image format | JPEG |
| Cloud JPEG quality | 70 |
| Cloud target cadence | 1 FPS |

Open hardware details still needed:

- Camera module name and output format.
- Native hardware stream resolution.
- Encoder type and bitrate.
- Expected stream FPS.
- RTSP server software and supported transport modes.
- Boot time from power-on to stream ready.
- Reconnect behavior after WiFi drop.
- Power and thermal limits.
- Firmware update or configuration process.

## 8. Completed Work

### 8.1 Personal Repo Setup

Completed:

- MITRA Android app snapshot uploaded into personal repo.
- `origin` points to `MdShabazS/MITRA-AndriodApp-V1`.
- Organization repo kept as `upstream`.
- Local upstream push disabled.
- Documentation standards added.
- Hardware integration docs added.

### 8.2 Production Readiness Audit

Completed:

- Compared Android repo against Android and Infra/MVP task lists.
- Ran local unit tests.
- Ran debug APK build.
- Ran Android lint.
- Documented risks and next backlog.

Evidence file:

```text
docs/PRODUCTION_READINESS_AUDIT_2026-08-07.md
```

Results:

- `:engine:testDebugUnitTest` passed.
- `:app:testDebugUnitTest` passed.
- `:app:assembleDebug` passed.
- `:app:lintDebug` passed with warnings.

### 8.3 Camera QA Mic Restart Fix

Problem:

- During camera testing, the mic was repeatedly turning on and off.
- Logs showed `VOICE_BG` SpeechRecognizer restarts while camera inference was active.

Fix:

- `CameraActivity` no longer starts background voice service automatically.
- `CameraActivity` no longer requests microphone permission automatically.
- Camera QA entry stops MITRA runtime and cancels pending voice runtime restarts.

Evidence file:

```text
docs/PHONE_QA_2026-08-07.md
```

Result:

- PASS on OPPO CPH2729 / Android SDK 36 for camera inference without mic restart loop.

### 8.4 Hardware RTSP Stream QA

Completed:

- Tested phone connected to MITRA hardware WiFi.
- Verified hardware stream through RTSP.
- Verified local engine attached to hardware frames.
- Verified frame capture from stream.
- Verified transport memory behavior.
- Verified navigation TTS from local results.

Evidence file:

```text
docs/HARDWARE_STREAM_CONTRACT_AND_QA.md
```

Device evidence:

- Phone: OPPO CPH2729 / CPH2729IN
- Android SDK: 36
- ADB serial: `92edef11`
- WiFi SSID: `MITRA_DEVICE`
- Hardware BSSID observed: `88:a2:9e:89:9a:48`
- Phone IP on MITRA WiFi: `10.42.0.168`
- PixelCopy frame observed: 640 x 480

Result:

- PASS for hardware RTSP video plus local inference.
- PASS for remembered RTSP transport selection.
- Cloud WebSocket response remains pending because the phone could not reach the cloud endpoint while bound to MITRA WiFi.

### 8.5 Wake Word And Command Candidate Fix

Problem:

- Speech recognition could return multiple candidates such as `Mathura time`, `Mitra time`, and `mitrata time`.
- Earlier logic could select a non-wake candidate first, then reject it.

Fix:

- Added candidate selection that prefers wake-word candidates.
- Tightened wake matching to whole word or whole phrase.
- Rejected near misses such as `mathura`, `myntra`, and `mitrata`.

Evidence file:

```text
docs/VOICE_COMMAND_BACKGROUND_QA_2026-08-07.md
```

Result:

- Unit tests passed.
- Earlier live logs showed `mitra time` and `mitra battery` worked.
- Full live command matrix still needs retesting on current APK.

### 8.6 Setup Prompt And RTSP Startup Tuning

Completed:

- Replaced misleading `Accessibility skipped` wording with delayed recheck and clearer message.
- Shortened MITRA WiFi search wait and retry delays.
- Shortened RTSP first-frame fallback timings.
- Added last-good RTSP transport memory.
- Added LibVLC video-output grace behavior so first-frame reconnect is not triggered too early.

Evidence files:

```text
docs/HARDWARE_STREAM_CONTRACT_AND_QA.md
docs/WORK_LOG.md
```

Result:

- Faster repeated hardware stream startup after the app learns the working transport.

### 8.7 Android Device Compatibility Plan

Completed:

- Defined the practical meaning of "app should run on every phone".
- Minimum support target is Android 10+ / API 29+.
- Target SDK is 36.
- Added representative OEM test matrix.

Evidence file:

```text
docs/ANDROID_DEVICE_COMPATIBILITY_PLAN.md
```

Current result:

- OPPO path has partial pass evidence.
- Samsung, Xiaomi/Redmi/POCO, Vivo/iQOO, Pixel, and OnePlus remain pending.

## 9. Current Known Risks

Do not mark the app production-ready until these are resolved or formally accepted:

1. Cloud response path is not verified on real hardware WiFi.
2. Android-to-cloud MessagePack schema is not fully canonical across docs and code.
3. Full live voice command matrix is not complete on the current APK.
4. Cross-OEM Android compatibility is not complete.
5. Long-run hardware stability test is not complete.
6. Hardware stream native specs are not fully documented.
7. Release privacy/security notes are incomplete.
8. Lint warnings need triage before release.
9. Knowledge graph output is missing and should be regenerated.

## 10. Pending Work

### P0 - Must Do Before Production

1. Verify cloud WebSocket route while connected to MITRA hardware WiFi.
2. Capture backend ACK and response logs for at least one hardware frame.
3. Choose one canonical Android-to-cloud MessagePack schema.
4. Update `STREAMING_METADATA_SCHEMA.md` and both sending paths to match the canonical schema.
5. Run complete live voice command matrix with wake word `mitra`.
6. Run 10-minute hardware stream stability test.
7. Capture logcat evidence for RTSP, frame sending, local inference, and TTS throttling.
8. Triage lint warnings that affect release, permissions, Play policy, native library alignment, localization, and identifiers.

### P1 - Needed For Team Handoff

1. Get final hardware stream specs from hardware engineer.
2. Add a decoded MessagePack payload sample from a real hardware run.
3. Add pass/fail manual QA checklist.
4. Add release readiness checklist.
5. Regenerate `graphify-out/` and commit the graph/report if team policy expects it in repo.
6. Create upstream PR or review package for `ihelprobotics/AndroidApp` only after personal repo work is reviewed.

### P2 - Product Improvements

1. Add debug-only voice command injection hook if live STT testing remains slow.
2. Add visible speech recognizer state for QA.
3. Improve cloud status UI with last ACK/last response details.
4. Add more pipeline-level tests for timeout, exception, night skip, indoor skip, model missing, and partial failure result behavior.
5. Add device-specific troubleshooting notes for ColorOS, One UI, HyperOS/MIUI, Vivo/iQOO, Pixel, and OxygenOS.

## 11. Testing Guide

Use these commands from the repo root on macOS:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="/Users/shabaz/Library/Android/sdk"
```

Unit tests:

```bash
./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process :engine:testDebugUnitTest :app:testDebugUnitTest
```

Debug APK build:

```bash
./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process :app:assembleDebug
```

Lint:

```bash
./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process :app:lintDebug
```

Install debug APK on connected phone:

```bash
adb install -r -d -g app/build/outputs/apk/debug/app-debug.apk
```

Useful ADB checks:

```bash
adb devices
adb logcat -c
adb logcat -v time -s VOICE_BG HAZARD EngineBridge RtspFrameSource CameraTest ActivityTaskManager ActivityManager
```

Expected APK artifact:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 12. Manual QA Checklist

Run these on every release candidate.

### Setup

- Fresh install opens without crash.
- Required permission prompts appear.
- Accessibility prompt is clear and no longer says `Accessibility skipped` incorrectly.
- Notification access prompt is clear.
- Battery optimization guidance is clear.

### Hardware Stream

- Connect to `MITRA_DEVICE`.
- Confirm phone receives `10.42.0.x` IP.
- Open MITRA stream.
- Confirm live video appears.
- Confirm sampled frame count increases.
- Confirm resolution appears.
- Confirm last-frame age updates.
- Close and reopen stream.
- Confirm last successful transport is tried first.
- Turn hardware off and confirm fallback/retry behavior is understandable.

### Local AI

- Confirm local engine attaches to hardware stream.
- Confirm `HAZARD_*` logs show frame results.
- Test indoor frame skips outdoor-only hazards.
- Test day/night behavior.
- Test OCR on visible text.
- Confirm TTS does not repeat the same warning too frequently.

### Cloud

- Confirm WebSocket connects.
- Confirm frame send logs.
- Confirm backend ACK.
- Confirm backend final response maps to same frame/sequence where possible.
- Confirm app speaks only the intended final user guidance.

### Voice

Say each command clearly and wait for TTS to finish before the next command:

- `mitra`
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

Do not test real calls or WhatsApp sends unless a safe test contact and message are agreed.

### Background Mode

- Start app.
- Press Home.
- Confirm foreground service remains alive.
- Say `mitra time`.
- Say `mitra open youtube`.
- Say `mitra go home`.
- Confirm recognizer resumes after TTS.
- Confirm voice pauses during calls and resumes after call ends.

### Compatibility

Minimum required phone families:

- OPPO/ColorOS
- Samsung/One UI
- Xiaomi/Redmi/POCO HyperOS or MIUI
- Vivo/iQOO
- Pixel/AOSP-like
- OnePlus/OxygenOS

## 13. Hardware Engineer Handoff

The Android app currently expects a stable RTSP server at:

```text
rtsp://10.42.0.1:8554/stream
```

The app can remember the last working RTSP transport, but hardware should still support repeat connections reliably. The stream should start quickly after the phone joins the hardware WiFi, and reconnects should not require app restart.

Please provide or confirm:

- Exact camera sensor and module.
- RTSP server implementation.
- Supported transport modes.
- Stream resolution.
- Stream FPS.
- Encoder and bitrate.
- Whether audio exists in the stream.
- Expected boot-to-stream-ready time.
- Reconnect behavior when phone leaves/rejoins WiFi.
- Whether the hardware can expose status, battery, temperature, or firmware version later.

Do not change SSID, IP, RTSP port, or path without updating Android docs and testing the app.

## 14. Backend / AI Engineer Handoff

The Android app sends frames plus local mobile feature outputs. The cloud route must be verified against a real phone connected to MITRA WiFi.

Backend needs to confirm:

- Final WebSocket host and port.
- Whether endpoint path must be `/ws` or `/ws/<device_id>`.
- Final MessagePack field names.
- Whether Android should send both raw frame and local feature outputs every frame.
- ACK payload shape.
- Final response payload shape.
- How cloud response maps to `seq` or `frame_index`.
- Whether the app should speak `final_text`, navigation-only fields, or both.

Known issue:

- `STREAMING_METADATA_SCHEMA.md`, `BackgroundService`, and `CameraActivity` have shown different payload shapes. Canonicalize before production.

## 15. App Engineer Handoff

When continuing Android work:

1. Start from this file and `docs/WORK_LOG.md`.
2. Check `git status --short --branch`.
3. Confirm `origin` is the personal repo.
4. Do not push to the organization repo directly.
5. Run tests before and after code changes when behavior changes.
6. Update docs in the same branch.
7. Add a short work-log entry for every meaningful task.
8. Push to the personal repo after validation.
9. Prepare an upstream PR only after review.

Useful current git history around this handoff:

```text
ac7a37c Add Android device compatibility plan
55a2fe2 Document hardware RTSP retest and vout grace
cd7d378 Tune setup prompts and remember RTSP transport
66e5d39 Fix wake word selection and document voice QA
85ec37e Document hardware RTSP stream QA
873cdcc Fix camera QA voice restart
f217666 Add production readiness audit
9e12716 Document team work logging process
```

## 16. Definition Of Done For Future Work

A future task is not complete until:

- Code change is implemented, if required.
- Relevant tests or manual QA are run.
- `docs/WORK_LOG.md` is updated.
- Any affected architecture, hardware, command, or schema docs are updated.
- Known risks are clearly called out.
- Personal repo is pushed.
- Organization repo remains untouched unless the user explicitly asks for upstream update.

