# MITRA Work Log

This file records completed project work in a format that app, AI, and hardware teammates can scan quickly.

## 2026-08-07

### Accessibility Prompt And RTSP Startup Tuning

Owner: MdShabazS / Codex

Summary:

- Replaced the misleading setup phrase `Accessibility skipped` with a delayed recheck and clearer message when accessibility is still not enabled.
- Shortened WiFi scan result wait and retry delays during the MITRA hardware search flow.
- Shortened RTSP first-frame fallback timings so TCP/UDP fallback happens faster when the first transport does not deliver frames.
- Added RTSP transport memory: when TCP or LibVLC automatic/UDP successfully produces the first frame, the app saves that transport and tries it first on the next hardware stream launch.
- Added a first-frame video-output guard so LibVLC `vout` can cancel an already scheduled reconnect and PixelCopy gets a short grace window to capture the first frame.

Files changed:

- `app/src/main/java/com/unique/visionmate/MainActivity.kt`
- `app/src/main/java/com/unique/visionmate/RtspFrameSource.kt`
- `docs/HARDWARE_STREAM_CONTRACT_AND_QA.md`
- `docs/WORK_LOG.md`

App impact:

- Setup feedback is clearer if accessibility is not actually enabled.
- Hardware stream startup should feel faster, especially after one successful session because the last working transport is tried first.
- Normal fallback remains in place if the remembered transport fails.

Hardware impact:

- No firmware contract changed.
- Android now adapts to the last observed working RTSP transport for faster repeat launches.

AI/model impact:

- No model or inference behavior changed.

Validation:

- `:app:assembleDebug` passed.
- `:app:testDebugUnitTest` passed.
- Updated APK installed on OPPO CPH2729 / Android SDK 36.
- ADB confirmed accessibility was not enabled on the phone during this check (`enabled_accessibility_services = null`), matching the setup issue being fixed.
- Retested after connecting the phone to `MITRA_DEVICE` with IP `10.42.0.168`.
- First hardware run started with TCP, fell back to LibVLC automatic/UDP, captured the first 640 x 480 frame, and saved `rtsp.last_good_transport=vlc-auto/udp`.
- Follow-up hardware run loaded the remembered transport and started with `initialTransport=vlc-auto/udp`.
- Confirmed hardware frames reached `VideoFrameCache`, local engine attached, `VOICE_BG frame_sent` emitted JPEG frames, and local-only navigation TTS produced guidance.
- Cloud WebSocket still failed from the MITRA WiFi route because the phone could not reach `ws://151.185.32.13:8765/ws` while bound to hardware WiFi.

Follow-ups:

- Test cloud ACK/response with a route that can reach both MITRA hardware and the cloud WebSocket endpoint.

### Voice Command Background QA And Wake-Word Fix

Owner: MdShabazS / Codex

Summary:

- Tested MITRA background runtime on the OPPO phone after installing a rebuilt APK.
- Confirmed the app process stayed alive after pressing Home and `VOICE_BG` continued running.
- Fixed speech candidate selection so Android STT candidates containing the wake word are preferred over earlier non-wake candidates that only contain command keywords.
- Tightened wake-word matching to the whole word/phrase `mitra`, `mi tra`, or `mithra`, so near-misses such as `mathura`, `myntra`, and `mitrata` do not wake MITRA.
- Added unit tests for the wake-word and candidate selector behavior.
- Documented the command matrix and live retest procedure for the team.

Files changed:

- `app/src/main/java/com/unique/visionmate/VoiceCommandSelector.kt`
- `app/src/main/java/com/unique/visionmate/BackgroundService.kt`
- `app/src/test/java/com/unique/visionmate/VoiceCommandSelectorTest.kt`
- `docs/VOICE_COMMAND_BACKGROUND_QA_2026-08-07.md`
- `docs/WORK_LOG.md`

App impact:

- Background commands are more likely to use the recognizer candidate containing `mitra`.
- False wakes from near-miss words are reduced.

Hardware impact:

- No hardware firmware behavior changed.
- `start MITRA` and hardware-frame commands still need final live retest with the hardware stream active.

AI/model impact:

- No model or inference behavior changed.

Validation:

- `:app:testDebugUnitTest` passed.
- `:app:assembleDebug` passed.
- Installed debug APK on OPPO CPH2729 / Android SDK 36.
- Confirmed `pidof com.unique.visionmate -> 15669` after pressing Home.
- Confirmed `VOICE_BG` logs continued in background.
- Current live speech run was inconclusive because the recognizer heard unrelated speech / `STT error code: 7`.

Follow-ups:

- Repeat the full live voice command matrix in a quiet room.
- Avoid real call/WhatsApp-send tests until a safe test contact and message are agreed.

### Hardware RTSP Stream Contract And QA

Owner: MdShabazS / Codex

Summary:

- Documented the MITRA hardware RTSP feed contract, Android LibVLC decode path, PixelCopy frame sampling, local AI frame path, and MessagePack/JPEG cloud upload format.
- Tested the hardware stream path on the OPPO phone with MITRA hardware connected.
- Confirmed the phone connected to `MITRA_DEVICE` and received IP `10.42.0.168`.
- Confirmed RTSP TCP opened but did not deliver PixelCopy frames before watchdog timeout, then LibVLC automatic/UDP fallback produced the first usable frame.
- Confirmed first decoded frame was captured at 640 x 480 and local hazard inference ran from the hardware frame cache.
- Confirmed cloud frame send attempts occurred, but the configured cloud WebSocket endpoint was unreachable from the MITRA WiFi route.

Files changed:

- `docs/HARDWARE_STREAM_CONTRACT_AND_QA.md`
- `docs/HARDWARE_INTEGRATION.md`
- `docs/PHONE_QA_2026-08-07.md`
- `docs/WORK_LOG.md`

App impact:

- No runtime app code changed in this entry.

Hardware impact:

- Documents the current Android expectations for MITRA WiFi, RTSP URL, transport fallback, decoded sample format, and pending firmware details to confirm.

AI/model impact:

- No model behavior changed.
- Confirms local inference can run on decoded hardware frames from `VideoFrameCache`.

Validation:

- OPPO CPH2729 / Android SDK 36 connected to `MITRA_DEVICE`.
- RTSP URL under test: `rtsp://10.42.0.1:8554/stream`.
- LibVLC automatic/UDP fallback captured first frame at 640 x 480.
- `EngineBridge` attached to `VideoActivity` frame source and submitted 50+ frames.
- `VOICE_BG frame_sent` logs reached `seq=20`.

Follow-ups:

- Capture native firmware encoder codec, resolution, FPS, bitrate, and GOP/keyframe interval.
- Test cloud ACK/response path with a network route that can reach both MITRA hardware and the cloud WebSocket endpoint.
- Run a longer 10-minute thermal/power stream test.

### Camera QA Mic Restart Fix

Owner: MdShabazS / Codex

Summary:

- During physical OPPO phone camera testing, the mic indicator/sound toggled repeatedly while `CameraActivity` was running.
- Logs showed `BackgroundService` speech recognition restarting during the camera test path.
- Updated `CameraActivity` so the debug camera inference path no longer starts the voice service or requests microphone permission automatically.
- Added a camera QA entry stop that clears `MitraRuntime` active state and sends `ACTION_STOP_MITRA_RUNTIME`, preventing pending voice-runtime restart alarms from reviving SpeechRecognizer during camera testing.
- Added phone QA evidence for the OPPO retest.

Files changed:

- `app/src/main/java/com/unique/visionmate/CameraActivity.kt`
- `docs/PHONE_QA_2026-08-07.md`
- `docs/WORK_LOG.md`

App impact:

- Camera test path now focuses on camera + local engine inference.
- Main app voice behavior is unchanged.

Hardware impact:

- No hardware stream behavior changed.

AI/model impact:

- No model or inference behavior changed.

Validation:

- `:app:assembleDebug` passed.
- Installed the rebuilt APK on OPPO CPH2729 / Android SDK 36.
- Clean-launched `CameraActivity` after force-stop and logcat clear.
- Confirmed continuous `CameraTest` / `HAZARD` inference logs through frame 25+.
- Confirmed no `VOICE_BG` logs during the patched camera run.
- Confirmed no `ACTION_RESTART_RUNTIME` intent during the patched camera run.

Follow-ups:

- If voice needs to be tested together with camera inference later, add an explicit debug toggle instead of starting mic automatically.

### Production Readiness Audit

Owner: MdShabazS / Codex

Summary:

- Reviewed Android and Infra/MVP production readiness task lists.
- Ran unit tests, debug APK build, and Android lint against the personal repo checkout.
- Added a written audit report with pass/fail status, gaps, and recommended next fixes.

Files changed:

- `docs/PRODUCTION_READINESS_AUDIT_2026-08-07.md`
- `docs/WORK_LOG.md`

App impact:

- No runtime app behavior changed.

Hardware impact:

- Identified that physical MITRA hardware evidence is still required before production readiness can be claimed.
- Identified that the Android/cloud MessagePack contract needs one canonical version for hardware/cloud handoff.

AI/model impact:

- Existing model assets and local inference code were not changed.
- Identified missing pipeline-level tests for timeout, exception, night skip, indoor skip, and model missing behavior.

Validation:

- `:engine:testDebugUnitTest` passed.
- `:app:testDebugUnitTest` passed.
- `:app:assembleDebug` passed.
- `:app:lintDebug` passed with warnings.

Follow-ups:

- Fix or document the production RTSP offload gate.
- Canonicalize streaming payload docs and code.
- Add manual QA/release checklist docs and physical-device evidence.

### Personal Repository Setup And Documentation Policy

Owner: MdShabazS / Codex

Summary:

- Uploaded the MITRA Android app snapshot from `AndroidApp-Mitra-AndriodApp-V1.zip` into the personal GitHub repository `MdShabazS/MITRA-AndriodApp-V1`.
- Configured the local workspace so `origin` points to the personal repo.
- Kept the organization repo `ihelprobotics/AndroidApp` as `upstream` only, with push disabled locally.
- Added documentation standards so future work is recorded for the team.
- Added a hardware integration guide for firmware/hardware coordination.

Files changed:

- `README.md`
- `AGENTS.md`
- `docs/DOCUMENTATION_STANDARDS.md`
- `docs/HARDWARE_INTEGRATION.md`
- `docs/WORK_LOG.md`

App impact:

- No runtime app behavior changed.

Hardware impact:

- Documented current WiFi and RTSP expectations for MITRA hardware.
- Added a checklist for hardware changes that affect the Android app.

AI/model impact:

- No model or inference behavior changed.
- Documented where active model files and manifest live.

Validation:

- Verified local Git remote setup uses personal repo as `origin`.
- Verified organization repo push URL is disabled locally.

Follow-ups:

- For every future task, update `docs/WORK_LOG.md` and the relevant project docs in the same branch.
