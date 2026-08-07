# AGENTS.md

Guidance for Codex working in **VisionMate / MITRA Android client** (`com.unique.visionmate`).

This file is the **first thing** to read on every session. It points at knowledge graphs (KGs) instead of source files so future turns spend tokens on reasoning, not re-reading code.

---

## 1. KG-first read protocol — DO THIS BEFORE OPENING SOURCE

Two repos cooperate; both have KGs. Read **only** what the user's question requires.

| Need | Read this **first** | Path |
|---|---|---|
| Anything about Android client structure, classes, call graph | Android KG | [graphify-out/graph.json](graphify-out/graph.json) |
| Cluster/community summaries for Android | Android report | [graphify-out/GRAPH_REPORT.md](graphify-out/GRAPH_REPORT.md) |
| Backend pipeline, gating rules (day/night, indoor/outdoor), GPU group features, msgpack schema | Backend KG | `D:\MITRA\graphify-out\graph.json` |
| Backend cluster summaries (which features live where) | Backend report | `D:\MITRA\graphify-out\GRAPH_REPORT.md` |
| Backend canonical narrative | `D:\MITRA\AGENTS.md`, `D:\MITRA\MITRA.md`, `D:\MITRA\BRANCH_AUDIT.md` | (read only the section you need) |

**Rules:**
1. **Do not Read/Grep source files** for symbol lookups, call graphs, "which class does X" — query the KG JSON instead.
2. **Do open source files** for the *exact* edit (lines you will change) and for verifying anything the KG asserts before acting on it. KGs go stale; the file on disk is truth at edit time.
3. If a query needs cross-repo reasoning (e.g., "mirror the backend day/night gate on device"), join Android KG + Backend KG; do **not** crawl `D:\MITRA\Cloud-infra\**` source.
4. If a KG file is missing or older than the last commit on its repo, **regenerate before answering**: invoke `/graphify` on that repo. The user has wired graphify; do not improvise a different KG format.
5. **Never** copy backend Python source into Android — port semantics from the KG node descriptions, then write idiomatic Kotlin.

KG schema (graphify): nodes (`id`, `kind`, `path`, `name`, `summary`), edges (`src`, `dst`, `kind` ∈ {calls, imports, reads, writes, gates, depends_on}), communities (clusters with summaries). Cite KG node IDs (not file paths) when explaining cross-cutting behavior.

---

## 2. What this repo is

Single Gradle module `:app` — Android client for the MITRA assistive-vision device.

- **Pairs over WiFi** with `MITRA_DEVICE` (hardware), then opens an RTSP stream `rtsp://10.42.0.1:8554/stream` in `VideoActivity` via Media3 ExoPlayer into a `TextureView`.
- **Voice loop** in `BackgroundService` (foreground service, mic + media playback): wake-word → command → either local action (open app, call, navigate, WhatsApp send, OCR, take picture) or remote Q&A over WebSocket+MessagePack to `ws://216.48.182.37:8766/ws/<device_id>`.
- **OCR today** uses ML Kit `text-recognition:16.0.0` against `VideoActivity.getScreenBitmap()`. Already on-device — do not "replace EasyOCR" here; the mobile path is already mobile-native.
- **Accessibility service** `AutoSendAccessibilityService` auto-confirms WhatsApp send dialogs.
- **No ML runtime declared yet** (no TFLite / ONNX-Mobile / PyTorch-Mobile). Engine module below is greenfield.

For class details, query the Android KG. Do not re-summarize source here.

---

## 3. Offload integration — the work this AGENTS.md exists to support

We are adding an **on-device hazard inference engine** that mirrors the backend's per-frame logic, without disturbing the existing voice/RTSP/Q&A flow.

### 3.1 Decided constraints (do not re-litigate without the user)

| Decision | Value |
|---|---|
| Frame source | **Reuse `VideoActivity.getScreenBitmap()`** at throttled cadence (target 1–2 fps). No CameraX, no new permission. |
| Runtime | **TFLite + GPU/NNAPI delegates** (single runtime). Convert backend `.pt`/`.onnx` assets; ONNX-Mobile is **not** a target. |
| OCR runtime | **Keep ML Kit** (already integrated). Do not switch to a TFLite OCR. |
| Day/Night gate | **Algorithmic luminance gate** on device (no model). Mirror semantics from backend KG node `day_night_gate`. |
| Module shape | **New Gradle module `:engine`** (not a sub-package of `:app`). Bounded threads, bounded queue, isolated from app process state. |
| Integration point | One observer interface `HazardObserver` consumed by existing app via event bus; **no edits to existing UI/voice flows** until local stability passes. |
| Feature flags | `SharedPreferences` (already used) keyed `offload.<feature>.enabled`; remote config is later work. |
| Telemetry | Local logcat tag `HAZARD_*` first; upload pipeline deferred. |

### 3.2 Execution graph (mirror of backend; verify against backend KG before changing)

```
frame → DayNightGate
         ├─ night → publish(skipped, reason=NIGHT) ; STOP
         └─ day  → SceneClassifier
                    ├─ FireSmoke   (always)
                    ├─ WetDry      (always)
                    ├─ OCR         (always)  [ML Kit]
                    └─ if scene == OUTDOOR:
                         ├─ Pothole
                         └─ ElectricPole
```

Backend equivalents to verify in `D:\MITRA\graphify-out\graph.json` before each port: `day_night_gate`, `weather_gate` (indoor/outdoor), `scene_classifier`, `fire_smoke`, `wet_dry`, `pothole`, `electric_pole`. If a node's `summary` disagrees with this diagram, **trust the KG and update this AGENTS.md**, do not silently diverge.

### 3.3 Output contract (`HazardFrameResult`)

```kotlin
data class HazardFrameResult(
    val frameId: Long,
    val tsMs: Long,
    val dayNight: DayNight,                  // DAY | NIGHT
    val sceneType: SceneType?,               // null when night
    val executedFeatures: Set<Feature>,
    val skippedFeatures: Map<Feature, SkipReason>,
    val detectionsByFeature: Map<Feature, List<Detection>>,
    val latenciesMs: Map<Feature, Long>,
    val skipReason: SkipReason?              // top-level (e.g., NIGHT, QUEUE_FULL)
)
```

Telemetry events (logcat first): `frame_skipped_night`, `feature_timeout`, `feature_disabled`, `queue_drop_stale`.

### 3.4 Resource budgets (enforce in `:engine`, not in callers)

- Max in-flight frames: **1**. Stale frame older than 500 ms → drop with `queue_drop_stale`.
- Per-feature timeout: **150 ms** GPU delegate, **400 ms` CPU fallback. Timeout → `feature_timeout`, do not block downstream.
- Thermal: when `PowerManager` reports `THERMAL_STATUS_SEVERE+`, halve cadence; at `CRITICAL`, suspend engine and emit one `feature_disabled` per feature.
- Memory: pre-allocate input tensors per feature; no per-frame allocations in the hot path.

### 3.5 Non-disruption invariants (CI / review must enforce)

1. `:app` module has **no new dependency** on `:engine` except a single observer registration in `VideoActivity.onCreate` behind `offload.enabled`.
2. With `offload.enabled=false`, the app must be **byte-identical in behavior** to today (verified by existing instrumentation tests passing unchanged).
3. `:engine` must **not** import from `:app` (one-way dependency). Shared types live in a tiny `:engine-api` module if needed.
4. `:engine` must not touch: speech recognizer, TTS, WebSocket, WiFi callbacks, accessibility service, foreground notification.

---

## 4. Models (asset packaging)

Models are **not in this repo** and must not be committed (large binaries → APK bloat, Git LFS not configured). Plan:

- Source: `D:\MITRA\Models\` (backend repo). For each target feature, the backend has at least one `.pt` or `.onnx`. Authoritative inventory lives in backend KG community **`Models`** — query it, do not `ls` the directory.
- Conversion: separate workstream, output goes to `app/src/main/assets/models/<feature>.tflite` with companion `<feature>.json` (input shape, mean/std, label map).
- **Manifest:** `app/src/main/assets/models/manifest.json` is the single source of truth Engine reads at startup. Mismatch → feature auto-disables with `feature_disabled` (do not crash).
- Known inconsistency carried over from backend: pothole asset name. Resolve in manifest, not in code.

---

## 5. Common commands

```powershell
# Build (Windows / PowerShell)
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:lint
.\gradlew.bat :app:testDebugUnitTest

# Once :engine module exists
.\gradlew.bat :engine:testDebugUnitTest
.\gradlew.bat :engine:connectedDebugAndroidTest    # device/emulator required

# Regenerate the KG for THIS repo (do this whenever you finish a non-trivial change)
# Invoke /graphify in Codex; it writes to graphify-out/.
# Backend KG is regenerated from D:\MITRA the same way.
```

Min/Target SDK: 29 / 36. JVM target 11. Namespace `com.unique.visionmate`.

---

## 6. Working agreements

- **Do not** add features, refactors, or "while I'm here" cleanups outside the task. Three lines of duplication beats a premature abstraction in `:engine`.
- **Do not** add backwards-compat shims or feature flags for hypothetical futures. The flags listed in §3.1 are the only ones.
- **Do not** modify `BackgroundService`, `MainActivity`, `VideoActivity`, or `AutoSendAccessibilityService` to "wire in" the engine until §3.5 invariants are testable. The first integration is the single observer in `VideoActivity`.
- When in doubt about backend semantics, **query the backend KG**. Do not open `D:\MITRA\Cloud-infra\**` source unless the KG is silent and the user has approved a deeper read.
- This file is part of the contract. Update it when a §3 decision changes; never let it drift.
- Every meaningful code, workflow, AI, model, or hardware-contract change must update Markdown documentation in the same branch. Read and follow [docs/DOCUMENTATION_STANDARDS.md](docs/DOCUMENTATION_STANDARDS.md), add a short entry to [docs/WORK_LOG.md](docs/WORK_LOG.md), and update [docs/HARDWARE_INTEGRATION.md](docs/HARDWARE_INTEGRATION.md) whenever WiFi, RTSP, camera, streaming, or hardware assumptions change.
- Work from the personal repo remote first. The organization repo must be treated as an upstream review target, not a default push target.

---

## 7. Pointers (for humans; KG already covers these for Codex)

- Manifest, services, permissions: [app/src/main/AndroidManifest.xml](app/src/main/AndroidManifest.xml)
- Voice + WebSocket + on-device OCR: [app/src/main/java/com/unique/visionmate/BackgroundService.kt](app/src/main/java/com/unique/visionmate/BackgroundService.kt)
- RTSP frame source (engine tap-point): [app/src/main/java/com/unique/visionmate/VideoActivity.kt](app/src/main/java/com/unique/visionmate/VideoActivity.kt)
- Setup flow / pairing: [app/src/main/java/com/unique/visionmate/MainActivity.kt](app/src/main/java/com/unique/visionmate/MainActivity.kt)
- App-level deps: [app/build.gradle.kts](app/build.gradle.kts)
- Project deps: [build.gradle.kts](build.gradle.kts), [settings.gradle.kts](settings.gradle.kts)
