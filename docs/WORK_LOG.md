# MITRA Work Log

This file records completed project work in a format that app, AI, and hardware teammates can scan quickly.

## 2026-08-07

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
