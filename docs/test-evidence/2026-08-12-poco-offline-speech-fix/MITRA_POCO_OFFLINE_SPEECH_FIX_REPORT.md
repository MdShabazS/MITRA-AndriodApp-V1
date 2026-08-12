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

Raw evidence files saved in this folder:

```text
poco_offline_speech_fix_logcat.log
poco_getprop.txt
poco_connectivity.txt
```
