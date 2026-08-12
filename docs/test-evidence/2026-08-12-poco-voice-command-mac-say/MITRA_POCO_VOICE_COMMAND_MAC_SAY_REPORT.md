# MITRA Poco Voice Command Mac-Say Test - 2026-08-12

Owner: MdShabazS / Codex

Device:

- Poco `25028PC03I`
- Package: `com.unique.visionmate`
- Latest debug APK installed before test: yes

## Scope

- Confirm the phone had the latest app update with the WebSocket frame-send guard.
- Use Mac text-to-speech so the phone listens to commands:
  - `mitra open youtube`
  - `mitra open whatsapp`
  - `mitra start mitra`
- Save logs for proof.
- Check whether cloud frames were still uploading while WebSocket/video were not available.

## Evidence Files

Raw logs are local evidence and may be ignored by Git:

- `logcat_after_launch_crash.log`
- `logcat_after_mac_say_commands.log`
- `connectivity_after_voice_test.txt`
- `wifi_after_voice_test.txt`

## Result

Latest APK install:

- `adb install -r app/build/outputs/apk/debug/app-debug.apk` returned `Success`.

Voice command result:

- The Mac spoke the command sequence.
- The phone did not reach MITRA command handling for those commands.
- Logcat showed Google/Android SpeechRecognizer failures:
  - `STT error code: 2`
  - `Speech recognizer error=2; retrying in 60000ms`
  - Google recognizer `GRPC_ERROR code 14`

Network state:

- `dumpsys connectivity` showed `Active default network: none`.
- `dumpsys wifi` showed WiFi disconnected after the run.
- With no active default network, Google SpeechRecognizer could not complete recognition.

Cloud frame behavior:

- No `frame_sent` lines appeared in the command-test logs.
- No `JPEG compression failed` or cloud frame send lines appeared.
- Logs repeatedly showed `No video frame available`, meaning the frame bus had no live frame to upload.
- This is consistent with the latest WebSocket guard: cloud frames are not prepared/sent unless WebSocket is connected and a frame exists.

## Conclusion

The app update was installed, but the Mac-speech command test did not validate app-launch commands because Android SpeechRecognizer failed before command text was produced.

This is not a YouTube/WhatsApp command-routing failure yet. It is a speech-recognition/network availability failure on the Poco test state.

## Next Test Setup

Before retesting Mac-spoken commands:

1. Ensure the phone has an active default internet network.
2. Confirm logcat shows SpeechRecognizer listening without immediate `error=2`.
3. Keep the phone close to the Mac speaker.
4. Clear logcat.
5. Speak one command at a time and wait for the app log to show recognized text.

If the phone must remain on `MITRA_DEVICE` with no internet, MITRA needs an offline/local command recognizer path for product reliability.
