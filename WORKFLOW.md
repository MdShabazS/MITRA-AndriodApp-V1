# MITRA / VisionMate — App Workflow

Assistive-vision app for blind / low-vision users. The MITRA hardware is a camera that streams over
its own Wi-Fi; the phone app is the brain — voice assistant + on-device hazard detection. When no
hardware is present, it falls back to the phone's own camera.

## Components

| Component | Role |
|---|---|
| `MainActivity` | Home screen, launcher, one-time onboarding, hardware-vs-camera routing, voice on/off button |
| `BackgroundService` | Foreground service: wake word + speech recognition, command handling, TTS, Wi-Fi hold, cloud streaming, call-state handling |
| `VideoActivity` | Shows the MITRA RTSP stream; attaches the hazard engine to stream frames |
| `CameraActivity` | Phone back-camera fallback; attaches the hazard engine to camera frames |
| `OfflineTestActivity` | Runs the engine on a local test video (dev/QA) |
| `EngineBridge` | The single link between `:app` and the `:engine` hazard module |
| `:engine` | TFLite hazard inference (day/night, scene, fire/smoke, wet/dry, pothole, electric pole, pedestrian) + ML Kit OCR |
| `AutoSendAccessibilityService` | Launches apps from the background, taps WhatsApp send, camera shutter, home/back |
| `MitraNotificationListener` | Reads incoming WhatsApp/SMS aloud; captures the reply action |

## 1. App start
1. Open app → `MainActivity` → TTS: "Voice enabled. Say start mitra to begin, or a command like open an app."
2. Voice control is **ON** by default (mic button highlighted blue). `BackgroundService` starts and listens.
3. On the home screen you can speak commands directly (no wake word). It stays **idle** — no camera, no Wi-Fi search — until you tap **START** or say **"start mitra"**.

## 2. Start MITRA (hardware detect → fallback)
Triggered by the START button or the voice command "start mitra" (works from the background too):
1. Scan briefly for the MITRA Wi-Fi (`MITRA_DEVICE`); if already connected, reuse the current network.
2. **Found** → ask the Wi-Fi password → connect → open `VideoActivity` (live RTSP stream).
3. **Not found** (or no location) → open `CameraActivity` (phone back camera).
4. Once the search starts, voice control turns off (the setup flow needs the mic).

## 3. Hazard engine (per frame)
Inside `VideoActivity`/`CameraActivity`, `EngineBridge` feeds frames (~1 fps) to `:engine`:
```
frame → DayNightGate
         ├─ night → skip model features (reason = NIGHT)
         └─ day  → SceneClassifier (indoor/outdoor)
                    ├─ FireSmoke, WetDry, Pedestrian, OCR
                    └─ if OUTDOOR: Pothole, ElectricPole
→ HazardFrameResult (detections, latencies) → spoken / logged (HAZARD_* tags)
```

To reduce false spoken hazards during long RTSP sessions, reused round-robin detections expire after
about 2500 ms. NIGHT clears cached detections, and INDOOR clears cached pothole/electric-pole results.
Spoken local hazard TTS does not raise or hide model detections. The model manifest remains the
detection source of truth; user-facing hazard speech requires confirmed fresh detections from real
feature runs, not one cached or stale frame.

## RTSP long-run behavior

In hardware mode, `RtspFrameSource` opens `rtsp://10.42.0.1:8554/stream` with LibVLC and samples the visible `SurfaceView` through PixelCopy. The app remembers the last transport that produced a frame and tries it first next time. The live screen keeps the feed centered, stream health on the left, and cloud send/reply health on the right.

After about 5 minutes of a live session, Android only refreshes the player if the sampled frame path is already stale. Healthy streams are left alone, preventing a timer-based interruption. If the sampled counter keeps increasing but the copied image fingerprint does not change for a sustained window, Android treats it as a visual freeze and reconnects. If a refresh is needed, reconnect state must remain pending until the restart starts, preventing the repeated 800 ms live-refresh loop reproduced on Poco before the 2026-08-12 fix.

Future hardware/iOS streaming decisions are tracked in `docs/PRODUCT_STREAMING_ARCHITECTURE.md`. Do not rewrite firmware or switch protocols without first backing up hardware code, capturing the current stream profile, and running the 15-minute baseline tests.

## 4. Voice commands (anytime, via BackgroundService)
- **Foreground (home screen):** speak commands directly.
- **Background (another app in front):** prefix with **"MITRA"** (wake word required to avoid false triggers).
- Command handling: `handleSpeechResult` → `processCommand`. A leading wake word and common mis-hears
  ("tu"→"to", "myntra"→"mitra") are normalized first; contact names use fuzzy matching.
- **Launching** (open/call/send/navigate) is routed through the **accessibility service**, so it works
  from the background where a normal service is blocked.
- Speech recognizer hard errors use backoff, because some phones do not have the requested offline language pack or lose recognizer service while connected to MITRA WiFi without internet.
- See `COMMANDS.md` for the full command list.

## 5. Messages, calls, coexistence
- **Incoming messages** (WhatsApp/SMS) are read aloud by `MitraNotificationListener`; "reply <msg>" sends via the notification's reply action. (Needs Notification access.)
- **Phone calls:** during a call the recognizer pauses; ~1.5s after the call ends it restarts automatically.
- The voice loop, RTSP/camera, and engine run together without disturbing each other.

## Permissions the user must enable
- **Microphone, Camera, Contacts, Phone** — core.
- **Accessibility** (MITRA) — for background app launching + auto-actions.
- **Notification access** — for reading messages aloud.
- **Display over other apps** + **battery: don't optimize** — for reliable background operation (esp. on Oppo/ColorOS).

## Restore point
A full backup was taken: `AndroidApp_BACKUP_<date>.tgz` (source + models + config, minus rebuildable `build/`).
