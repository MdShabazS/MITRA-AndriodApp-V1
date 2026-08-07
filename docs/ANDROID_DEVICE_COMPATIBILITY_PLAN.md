# MITRA Android Device Compatibility Plan

Owner: MdShabazS / Codex

Purpose:

- Track the release requirement that MITRA should run reliably across supported Android phones, not only the current OPPO test phone.
- Define the minimum phone/device matrix before team or organization handoff.
- Record known OEM-sensitive areas: accessibility, background microphone, WiFi route binding, RTSP decode, PixelCopy, and battery optimization.

## Compatibility Target

MITRA should support Android phones that meet these requirements:

| Area | Target |
|---|---|
| Android version | Android 10+ / API 29+ |
| App target SDK | 36 |
| CPU/ABI | Standard Android phones supported by the app's native dependencies |
| Camera fallback | Back camera available |
| Hardware mode | WiFi can connect to `MITRA_DEVICE` and route `10.42.0.1:8554` |
| Voice mode | Microphone permission granted and Android SpeechRecognizer available |
| Background controls | Accessibility service enabled |
| Overlay/status behavior | Display-over-apps permission enabled where required |

This does not mean every Android device in the market can be guaranteed without testing. It means the release should pass a representative device matrix and fail gracefully on unsupported devices.

## Minimum Device Matrix Before Release

| Device/OEM | Android version target | Required status |
|---|---:|---|
| OPPO / ColorOS | Android 15/16 class device | Already tested partially; continue as reference device |
| Samsung / One UI | Android 13+ | Pending |
| Xiaomi/Redmi/POCO / HyperOS or MIUI | Android 12+ | Pending |
| Vivo/iQOO / Funtouch OS | Android 12+ | Pending |
| Pixel / AOSP-like | Android 13+ | Pending |
| OnePlus / OxygenOS | Android 13+ | Pending |

## Required Test Cases Per Phone

Each phone should run the same checklist:

| Area | Test | Pass criteria |
|---|---|---|
| Install | Install debug/release APK | APK installs without ABI/dependency failure |
| First launch | Open MITRA | App does not crash and shows main screen |
| Permissions | Battery, microphone, camera, location, nearby WiFi, overlay | App guides user and continues safely |
| Accessibility | Enable MITRA accessibility service | Service connects; background app controls work |
| Hardware WiFi | Connect to `MITRA_DEVICE` | Phone receives MITRA-network IP |
| RTSP | Start hardware stream | First frame captured through PixelCopy |
| Transport memory | Relaunch after one successful stream | App tries last successful RTSP transport first |
| Local inference | Stream for at least 2 minutes | Engine attaches and emits local results without crash |
| TTS guidance | Local navigation guidance | TTS speaks useful guidance and throttling prevents spam |
| Camera fallback | Start without MITRA hardware | Phone camera fallback opens and avoids mic restart loop |
| Voice commands | Wake word and simple commands | `mitra time`, `mitra battery`, `mitra go home` work in quiet room |
| Background mode | Press Home while MITRA active | Foreground service stays alive; commands still listen when allowed |
| Cloud route | Network route with cloud access | WebSocket connects and ACK/response path works |

## Known Compatibility Risks

- OEMs handle accessibility-service binding differently; OPPO can show Accessibility settings until the service fully binds.
- Some OEMs aggressively kill foreground services unless battery optimization is disabled.
- Android WiFi APIs can hide SSID/scan results unless Location and Nearby WiFi permissions are granted.
- Phones may route cloud traffic differently when bound to `MITRA_DEVICE`, which has no internet.
- LibVLC/PixelCopy first-frame timing varies across phones and GPUs.
- Android SpeechRecognizer behavior depends on installed Google speech services, language pack, microphone quality, and room noise.

## Release Gate

Do not claim "runs on every phone" until:

- The minimum device matrix above has pass/fail evidence.
- Failures have clear fallback behavior or documented device limitations.
- `docs/WORK_LOG.md` contains the tested phones, Android versions, and log evidence.
- `docs/PHONE_QA_2026-08-07.md` or a newer phone QA file is updated with device-specific results.

## Current Status

As of 2026-08-08:

- OPPO CPH2729 / Android SDK 36 has passing evidence for hardware RTSP, local inference, PixelCopy frame capture, and local navigation TTS.
- Cross-OEM compatibility is pending.
- Cloud WebSocket route is still pending while connected to `MITRA_DEVICE`.
