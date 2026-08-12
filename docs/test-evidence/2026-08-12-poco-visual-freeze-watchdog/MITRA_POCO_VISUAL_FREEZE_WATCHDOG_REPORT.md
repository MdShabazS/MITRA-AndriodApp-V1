# MITRA Poco Visual-Freeze Watchdog Report - 2026-08-12

Owner: MdShabazS / Codex

Scope:

- Review the user-reported issue where `Sampled` kept increasing from about `200` to `1300`, but the visible hardware video was stuck.
- Add app-side recovery for frozen decoded images.
- Keep cloud testing pending.

## Problem

The existing RTSP watchdog checked whether PixelCopy succeeded recently. That catches missing frames, but it does not catch this case:

```text
Sampled count keeps increasing
Latest frame age looks fresh
Visible video image does not change
```

In that state, Android is successfully copying the same frozen decoded surface repeatedly. The app thinks frames are fresh because the copy time is fresh, even though the visual content is stale.

## Fix

File changed:

- `app/src/main/java/com/unique/visionmate/RtspFrameSource.kt`

New behavior:

- Every successful PixelCopy frame gets a lightweight visual fingerprint from a 12 x 8 grid of sampled pixels.
- The app tracks the last time that fingerprint changed.
- If copied frames keep arriving but the visual fingerprint stays identical for 30 seconds and at least 30 copied frames, the watchdog schedules:

```text
reconnecting:visual-freeze:<transport>
```

and logs:

```text
watchdog: visual freeze ... reconnecting
```

This specifically targets the case where `Sampled` increases while the image is stuck.

## Why This Is Separate From Frame Age

`Last frame: 200ms ago` means Android copied the surface 200 ms ago.

It does not prove the camera image changed 200 ms ago.

The visual-freeze watchdog adds that missing check.

## Validation

Completed:

- `:engine:testDebugUnitTest` passed.
- `:app:testDebugUnitTest` passed.
- `:app:lintDebug` passed.
- `:app:assembleDebug` passed.
- Debug APK installed successfully on Poco `25028PC03I`.

Pending physical validation:

- Start the MITRA hardware stream.
- Keep the camera pointed at a scene with small visible motion or lighting changes.
- Confirm that if the visible video freezes while `Sampled` continues increasing, logcat shows `visual-freeze` and the app reconnects automatically.
- Confirm that a genuinely healthy still scene does not reconnect repeatedly.

Cloud remains pending.
