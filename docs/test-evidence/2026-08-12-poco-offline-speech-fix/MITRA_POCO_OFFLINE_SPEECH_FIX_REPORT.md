# MITRA Poco Offline Speech Fix Report - 2026-08-12

Owner: MdShabazS / Codex

Scope:

- Explain why OPPO accepted commands offline while Poco failed on MITRA WiFi.
- Change Android recognizer setup so setup/runtime command paths prefer offline/on-device recognition.
- Retest the patched APK on Poco and save evidence.

## Root Cause

OPPO worked because its Android/Google speech stack had a usable offline recognizer pack.

Poco did not behave the same. During MITRA WiFi testing, the phone had no active default internet network, and the Google speech path failed before MITRA command parsing received any text.

This means the app command layer was not the first failure. The phone-specific Android SpeechRecognizer implementation was the blocker.

## App Change

The app now uses shared speech recognizer setup in:

```text
app/src/main/java/com/unique/visionmate/MitraSpeechRecognizerConfig.kt
```

Behavior:

- Prefer Android API 31+ `createOnDeviceSpeechRecognizer()` when the phone exposes on-device recognition.
- Use `en-US` for fixed MITRA commands because it is the most common offline English pack.
- Send `RecognizerIntent.EXTRA_PREFER_OFFLINE=true`.
- Remove language preference extras that do not belong in `ACTION_RECOGNIZE_SPEECH`.
- Log recognizer mode and offline preference in both setup and background runtime paths.

## Poco Retest

Device:

```text
POCO 25028PC03I / Android 15 / SDK 35
```

Installed APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Command spoken from Mac:

```text
mitra what is the time
```

Key logcat result:

```text
I/VOICE_BG: SpeechRecognizer mode=default-offline-preferred locale=en-US preferOffline=true
D/VOICE_BG: STT error code: 13
W/VOICE_BG: Speech recognizer error=13; retrying in 60000ms
```

Connectivity evidence:

```text
Active default network: none
```

Android `SpeechRecognizer` error `13` is `ERROR_LANGUAGE_UNAVAILABLE`: the requested language is supported by the recognizer, but the offline model is not currently available/downloaded on the phone.

## Conclusion

The app-side online/offline configuration is now corrected, but Poco still cannot recognize commands offline because its system recognizer has no available offline language model.

For a product that works equally on OPPO, Poco, Samsung, Vivo, Pixel, and future iOS, MITRA must not depend only on each phone's bundled Google/Android speech packs for fixed commands.

## Required Product Follow-Up

Add an embedded offline command-recognition path for fixed MITRA commands:

- Wake/control grammar: `mitra`, `start mitra`, `stop`, `go home`, `go back`, `time`, `battery`, `open <app>`, etc.
- Must work while connected to `MITRA_DEVICE` WiFi with no internet.
- Must have Android implementation first, then a matching iOS design using the same command grammar.
- Android `SpeechRecognizer` can remain as a secondary/general dictation path for names, WhatsApp messages, and free-form Q&A when internet or device recognizer support exists.

## 2026-08-12 Recovery Fix

Follow-up app change:

- When Android returns `ERROR_LANGUAGE_UNAVAILABLE`, MITRA now checks for validated internet.
- If validated internet is available, MITRA requests the Android offline speech model download.
- During that internet-backed recovery state, MITRA temporarily allows online recognition so Poco can accept commands while the offline model is being prepared.
- If no validated internet exists, MITRA logs the blocker clearly and tells setup users to connect the phone to internet once.

Verified on Poco with the current no-internet state:

```text
W/VOICE_BG: offline speech model missing; no validated internet for model download source=runtime-error-13
W/VOICE_BG: offline speech model unavailable and no internet fallback; connect this phone to internet once to prepare voice commands
```

This is the maximum fix possible while still depending on Android's system recognizer: the app can prepare/download the missing offline pack when internet exists, but it cannot manufacture that language model while the phone is already on a no-internet MITRA route.

## 2026-08-12 Internet Preparation Retest

User connected Poco to validated WiFi internet:

```text
Active default network: 105
SSID: "Shabaz 5G"
Capabilities: INTERNET&VALIDATED
```

Follow-up app change:

- MITRA now proactively calls the Android speech model preparation path whenever setup/runtime recognizer starts on a validated internet network.
- This removes the need to wait for the first offline failure before preparing the model.

Observed result after installing the proactive build:

```text
I/MITRA_SETUP_STT: setup recognizer mode=default-offline-preferred locale=en-US preferOffline=true
I/VOICE_BG: SpeechRecognizer mode=default-offline-preferred locale=en-US preferOffline=true
D/VOICE_BG: Heard: internet ke facilities nahin rahti
D/VOICE_BG: Command raw: internet ke facilities nahin rahti
```

The controlled Mac `mitra battery level` attempt was masked by nearby room speech, but the critical blocker changed: Poco was recognizing speech again while on internet instead of returning `ERROR_LANGUAGE_UNAVAILABLE`.

Next validation:

1. Keep the phone quiet and repeat `mitra battery level` while on internet.
2. Reconnect Poco to MITRA hardware WiFi/no internet.
3. Repeat `mitra battery level` and confirm the offline model now works without `ERROR_LANGUAGE_UNAVAILABLE`.

Raw evidence files saved in this folder:

```text
poco_offline_speech_fix_logcat.log
poco_speech_recovery_logcat.log
poco_internet_speech_prepare_logcat.log
poco_getprop.txt
poco_connectivity.txt
```
