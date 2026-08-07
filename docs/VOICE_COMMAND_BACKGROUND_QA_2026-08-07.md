# MITRA Voice Command Background QA - 2026-08-07

Owner: MdShabazS / Codex

## Scope

This note records the wake-word and background command tests for the Android MITRA app.

Canonical wake word: `mitra`

Accepted wake-word variants in code:

- `mitra`
- `mi tra`
- `mithra`

Near-miss words such as `mathura`, `myntra`, and `mitrata` are intentionally not accepted as wake words.

## Device Under Test

- Phone: OPPO CPH2729 / CPH2729IN
- Android SDK: 36
- ADB serial: `92edef11`
- App package: `com.unique.visionmate`
- Branch: `main`
- Repo: personal repo `MdShabazS/MITRA-AndriodApp-V1`

## Code Change Under Test

Speech recognition may return multiple candidates. In one physical test, Android returned:

```text
candidates=[Mathura time, Mitra time, mitrata time]
```

The previous selection logic could choose `Mathura time` first because it contained the command keyword `time`, then reject it because it did not contain the wake word. The selector now prefers candidates that contain the wake word before candidates that merely contain a command keyword.

The wake-word selector was also tightened to whole-word or whole-phrase matching, so `mitrata` no longer counts as `mitra`.

Files:

- `app/src/main/java/com/unique/visionmate/VoiceCommandSelector.kt`
- `app/src/main/java/com/unique/visionmate/BackgroundService.kt`
- `app/src/test/java/com/unique/visionmate/VoiceCommandSelectorTest.kt`

## Automated QA

Command:

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
ANDROID_HOME="/Users/shabaz/Library/Android/sdk" \
./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process :app:testDebugUnitTest
```

Result: Passed.

Coverage added:

- Candidate selector prefers `Mitra time` over earlier non-wake candidate `Mathura time`.
- Candidate selector prefers `mitra open youtube` over `open youtube` when both are returned.
- Wake matcher accepts `mitra`, `mi tra`, and `mithra`.
- Wake matcher rejects `mathura`, `myntra`, and `mitrata`.
- Wake remover extracts commands such as `open youtube` and `battery` after the wake word.

## Device QA

### Build And Install

Command:

```bash
./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process :app:assembleDebug
adb install -r -d -g app/build/outputs/apk/debug/app-debug.apk
```

Result: Passed.

### Background Runtime

Steps:

1. Force-stopped `com.unique.visionmate`.
2. Launched the app from the launcher with ADB monkey.
3. Pressed Home.
4. Checked the app process and `VOICE_BG` logs.

Evidence:

```text
pidof com.unique.visionmate -> 15669
08-07 17:28:54.668 D/VOICE_BG(15669): Starting frame streaming
08-07 17:29:24.315 W/VOICE_BG(15669): No video frame available
```

Result: Passed. MITRA remained alive after moving to the background.

### Live Voice Recognition

Planned phrases:

- `mitra battery`
- `mitra time`
- `mitra date`
- `mitra open youtube`
- `mitra go home`

Result: Inconclusive on the current live run.

Observed log behavior:

```text
08-07 17:30:12.716 D/VOICE_BG(15669): Heard: nana ke sath | candidates=[Nana ke sath, Nana ke sath mat ko, Nana ke sath nako] | Normalized: nana ke sath
08-07 17:30:21.237 D/VOICE_BG(15669): STT error code: 7
08-07 17:30:29.799 D/VOICE_BG(15669): STT error code: 7
```

Interpretation:

- The background service stayed alive and the recognizer continued cycling.
- The phone did not reliably receive the intended test phrases during this log window.
- This is not counted as a command pass or command failure.

Earlier live evidence before the whole-word tightening patch showed:

```text
Heard: mitra time | candidates=[Mathura time, Mitra time, mitrata time] | Normalized: mitra time
Command raw: time | normalized: time
Heard: mitra battery | candidates=[Mitra battery, main thoda battery] | Normalized: mitra battery
Command raw: battery | normalized: battery
```

The current code keeps that candidate-selection behavior and adds stricter wake-word matching.

## Command Test Matrix

| Command area | Example phrase | Expected result | Current QA status |
| --- | --- | --- | --- |
| Wake only | `mitra` | Acknowledge and wait for next command | Needs live retest |
| Time | `mitra time` | Speak current time | Code selector passed; earlier live pass |
| Date | `mitra date` | Speak current date | Needs live retest |
| Battery | `mitra battery` | Speak battery percentage | Code selector passed; earlier live pass |
| Open YouTube | `mitra open youtube` | Open YouTube from background | Earlier live pass; needs current APK retest |
| Open WhatsApp | `mitra open whatsapp` | Open WhatsApp from background | Needs live retest |
| Generic open | `mitra open contacts` | Open matching installed app | Needs live retest |
| Home | `mitra go home` | Accessibility service sends Home | Needs live retest |
| Back | `mitra back` | Accessibility service sends Back | Needs live retest |
| Torch | `mitra torch on/off` | Toggle flashlight | Needs live retest |
| Volume | `mitra volume up/down` | Adjust media volume | Needs live retest |
| Read text | `mitra read text` | OCR current camera/frame view | Needs active frame live retest |
| Take photo | `mitra take photo` | Trigger camera capture action | Needs active camera live retest |
| Start MITRA | `mitra start` | Bring app forward and start search | Needs live retest with hardware |
| Stop listening | `mitra stop listening` | Sleep until wake word | Needs live retest |

## Recommended Live Retest Procedure

Use a quiet room and keep the phone within 20-40 cm of the speaker.

Run:

```bash
adb logcat -c
adb logcat -v time -s VOICE_BG ActivityTaskManager ActivityManager
```

Then say one command at a time and wait for the app to finish speaking before saying the next command.

Pass criteria:

- Each command has a `Heard:` log that includes `mitra`.
- Each command has a matching `Command raw:` log.
- App-opening commands show `ActivityTaskManager` starting the target app from MITRA's UID.
- Home/back/system commands visibly change the phone state.

Do not use real call or WhatsApp-send commands during QA unless a safe test contact and message are agreed first.

## Follow-Ups

- Repeat the live command matrix in a quiet room and mark each command pass/fail.
- Add a non-production command injection test hook only for debug builds if live STT testing remains difficult.
- Consider adding visible QA status in the app for `SpeechRecognizer` state: ready, listening, no match, busy, network/server error.
