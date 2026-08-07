# Production Readiness Audit - 2026-08-07

Scope:

- Compared the current Android repo against:
  - `/Users/shabaz/Downloads/Android_PRODUCTION_READINESS_ASSIGNMENT.md`
  - `/Users/shabaz/Downloads/Infra_MVP_PRODUCTION_READINESS_TASKS.md`
- Ran local build/test checks on the personal repo checkout.
- No organization repo changes were made.

## Verification Commands

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
ANDROID_HOME="/Users/shabaz/Library/Android/sdk" \
./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process \
  :engine:testDebugUnitTest :app:testDebugUnitTest
```

Result: passed.

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
ANDROID_HOME="/Users/shabaz/Library/Android/sdk" \
./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process \
  :app:assembleDebug
```

Result: passed.

APK artifact:

```text
app/build/outputs/apk/debug/app-debug.apk
```

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
ANDROID_HOME="/Users/shabaz/Library/Android/sdk" \
./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process \
  :app:lintDebug
```

Result: passed with warnings.

Generated lint reports:

```text
app/build/reports/lint-results-debug.html
app/build/reports/lint-results-debug.txt
app/build/reports/lint-results-debug.xml
```

## Summary

The repo builds and its current JVM/unit tests pass. It has meaningful engine and navigation test coverage, but it is not production-ready against the supplied assignment yet.

Main status:

| Area | Status | Notes |
|---|---|---|
| Unit tests | Pass | Engine and app unit tests pass locally. |
| Debug APK build | Pass | `:app:assembleDebug` succeeds. |
| Lint | Pass with warnings | 148 warnings, including policy/release risks. |
| Engine manifest/flags/mailbox tests | Partially done | Good coverage exists. |
| Pipeline failure-path tests | Not done | Timeout/exception/partial failure behavior is implemented but not directly unit-tested. |
| Production RTSP engine gate | Needs fix | `VideoActivity` uses `attachAlways`, bypassing `offload.enabled`. |
| Android-cloud payload contract | Needs fix | Current docs and `CameraActivity` keys do not match; MVP contract uses different snake_case keys. |
| Manual QA checklist/release notes | Not done | Required by assignment, not yet present. |
| Hardware field evidence | Blocked | Requires physical MITRA hardware/device run and logs. |
| Backend/GPU infra tasks | Out of repo scope | Need backend/GPU repos and live environment evidence. |

## Android Assignment Check

### Engineer A - Engine Reliability

What looks done:

- `FeatureFlags` tests cover global default disabled, global enabled, per-feature default enabled, and disabling one feature.
- `ModelManifest` tests cover valid manifest, malformed manifest, unknown feature key, disabled feature, missing asset path, missing mean/std defaults, malformed mean/std defaults, labels, postprocessor, and confidence threshold.
- `Mailbox` tests cover latest-frame displacement and close/unblock behavior.
- `DayNightGate` tests cover luminance calculations.
- Runtime code catches per-feature timeouts and exceptions and records skip reasons.
- Missing or disabled model specs disable model-backed features instead of crashing startup.

Gaps:

- Pipeline-level tests are missing for:
  - night frame skips model-backed features;
  - indoor scene skips outdoor-only features;
  - missing model produces `MODEL_MISSING`;
  - feature timeout produces `FEATURE_TIMEOUT`;
  - feature exception produces `EXCEPTION`;
  - partial failure still returns a readable `HazardFrameResult`.
- Assignment timeout budget says GPU 150 ms and CPU fallback 400 ms. Current defaults are:
  - `gpuTimeoutMs = 3000`
  - `cpuTimeoutMs = 3000`
  - `ocrTimeoutMs = 1500`
- `EngineConfig.maxInFlight` exists, but the implementation currently hardcodes `Mailbox()` behavior rather than using the config value.
- Telemetry exists, but no offline/camera log sample artifact is committed yet.

Recommended next fixes:

- Add fake `FeatureRunner` pipeline tests.
- Decide whether current 3000 ms timeouts are intentional for OPPO/hardware testing; if yes, document the deviation from the assignment.
- Add a small QA log sample under a docs/test-evidence folder after running on a real device.

### Engineer B - App Integration, QA, Release

What looks done:

- `EngineBridge` has attach generation handling for double attach and activity-destroy-during-init races.
- `EngineBridge` logs null frame provider behavior.
- `CameraActivity` and `OfflineTestActivity` use explicit test/demo paths.
- Debug manifest exports test activities only in debug builds.
- `VideoActivity` exposes visible stream status: hardware status, sampled frames, FPS, resolution, and last-frame age.
- `NavigationStateStore` implements TTS throttling/dedup rules.
- `AndroidNavigationModuleTest` covers night, fire/smoke, depth obstacle, cloud object distance alias, cloud hazard, wrapped cloud data, clear indoor path, and local wet hazard behavior.

Gaps:

- Production RTSP path does not currently preserve the stated `offload.enabled=false` invariant. `VideoActivity.attachEngine()` calls `EngineBridge.attachAlways(...)`, which bypasses the global flag.
- The assignment requires manual QA checklist and release-readiness checklist. The repo has workflow docs, but no pass/fail QA checklist, release notes, device matrix, known-risk list, or do-not-ship list yet.
- Physical-device cases are not verified in this audit:
  - repeated open/close;
  - activity destroyed during engine init;
  - app background/foreground;
  - camera permission denied;
  - microphone permission denied;
  - RTSP unavailable/stale;
  - offline/camera inference log capture.
- Lint reports 148 warnings. Most are not build blockers, but some are release-relevant:
  - exact alarm / missing permission warning;
  - battery optimization policy warning;
  - native library 16 KB alignment warnings;
  - fixed orientation warnings;
  - hardware ID warning for Android ID;
  - many hardcoded/non-localized strings.

Recommended next fixes:

- Change `VideoActivity` production attach path to `EngineBridge.attachIfEnabled(...)` or explicitly document why production RTSP intentionally bypasses the flag.
- Add `docs/MANUAL_QA_CHECKLIST.md`.
- Add `docs/RELEASE_READINESS_CHECKLIST.md`.
- Run a physical-device QA pass and paste log samples into an evidence doc.

## Infra MVP Task Check

Person 1 and Person 2 tasks are backend/GPU ownership blocks. This Android repo cannot prove those tasks without the backend/GPU repos and running infrastructure.

Person 3 Android status:

| Task | Status | Notes |
|---|---|---|
| P3-B1 hardware camera stream path | Partially done | Hardware RTSP path exists and visible status exists. Physical hardware stream evidence still needed. |
| Back camera mode avoids reconnect loops | Likely done | `CameraActivity` is separate and does not run MITRA WiFi bind. Needs device QA evidence. |
| Device/cloud/last-response state | Partial | Stream status exists; cloud connection/last response is less explicit in production UI. |
| P3-B2 Android feature payload | Partial | `BackgroundService` sends local mobile feature outputs; `CameraActivity` sends another shape. Need one canonical contract. |
| Stable `device_id` | Partial | Uses stored unique code where available; `CameraActivity` falls back to Android ID, which lint flags. |
| P3-B3 navigation module integration | Partial | Navigation module exists and is unit-tested. Need end-to-end evidence that cloud response maps to same frame/seq and TTS speaks only `final_text`. |
| P3-B4 TTS throttling | Partial | `NavigationStateStore` implements throttling, but no 10-minute stream evidence yet. |

Contract mismatch to fix:

- Assignment MVP endpoint says `ws://<CPU_HOST>:8765/ws/{device_id}`.
- App default endpoint is `ws://151.185.32.13:8765/ws`.
- `CameraActivity` test endpoint is `ws://127.0.0.1:8766/ws`.
- `BackgroundService` payload uses top-level `device_id`, `seq`, `timestamp`, `frame`, `meta`, `sensor`, and `mobile_feature_outputs`.
- `CameraActivity` payload uses top-level `type`, `meta`, and `frame`, with camelCase keys such as `deviceId`, `frameId`, `tsMs`, `dayNight`, and `latencies`.
- `STREAMING_METADATA_SCHEMA.md` documents a third shape using keys such as `deviceId`, `sceneType`, `executedFeatures`, `skippedFeatures`, `detectionsByFeature`, and `latenciesMs`.

Recommendation:

- Choose one canonical Android-to-cloud MessagePack contract and update both code and `STREAMING_METADATA_SCHEMA.md`.

## Do Not Ship Yet

- Do not ship until production RTSP engine startup respects the `offload.enabled` requirement or the requirement is formally changed.
- Do not ship until the Android/cloud MessagePack schema is canonical and verified with a decoded payload sample.
- Do not ship until manual QA is run on at least one physical Android device with MITRA hardware.
- Do not ship until privacy/security release notes document cleartext WebSocket usage, battery optimization behavior, Android ID/device ID handling, and sensitive log boundaries.
- Do not ship until exact alarm/battery optimization lint risks are reviewed against Play policy and target distribution plan.

## Recommended Next One-Week Backlog

1. Fix or document the `VideoActivity` production offload gate.
2. Add pipeline fake-runner tests for timeout, exception, night skip, indoor skip, and model missing.
3. Canonicalize `STREAMING_METADATA_SCHEMA.md` against actual Android code and backend expectations.
4. Add manual QA and release-readiness checklist docs.
5. Run device QA with one offline video, one camera session, and one MITRA RTSP session; save log samples.
6. Review lint release warnings and classify each as fix now, acceptable debug-only, or known release risk.
7. Add a field-test handoff note for the hardware engineer covering stream URL, frame rate, expected endpoint, and decoded payload sample.
