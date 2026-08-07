#!/usr/bin/env zsh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
LOG_ROOT="${MITRA_LOG_DIR:-$ROOT_DIR/LOGS}"
SESSION_NAME="${1:-$(date +%Y-%m-%d_%H-%M-%S)}"
SESSION_DIR="$LOG_ROOT/$SESSION_NAME"
FILTER='RtspFrameSource|VideoActivity|EngineBridge|HAZARD|VOICE_BG|Navigation|AndroidNavigationModule|NavigationStateStore|VideoFrameCache|CloudFrameResultStore|AndroidRuntime|FATAL EXCEPTION|SIGSEGV|libvlc|VLC|Wifi|WiFi|ConnectivityManager|NetworkCallback'

find_adb() {
    local candidates=(
        "${ANDROID_HOME:-}/platform-tools/adb"
        "${ANDROID_SDK_ROOT:-}/platform-tools/adb"
        "$HOME/Library/Android/sdk/platform-tools/adb"
        "/opt/homebrew/bin/adb"
        "/usr/local/bin/adb"
    )

    local candidate
    for candidate in "${candidates[@]}"; do
        if [[ -n "$candidate" && -x "$candidate" ]]; then
            print -r -- "$candidate"
            return 0
        fi
    done

    if command -v adb >/dev/null 2>&1; then
        command -v adb
        return 0
    fi

    return 1
}

ADB="$(find_adb || true)"
if [[ -z "$ADB" ]]; then
    echo "adb not found. Open Android Studio once, or install Android SDK platform-tools." >&2
    exit 1
fi

mkdir -p "$SESSION_DIR"

START_EPOCH="$(date +%s)"
START_ISO="$(date '+%Y-%m-%d %H:%M:%S %Z')"
FULL_LOG="$SESSION_DIR/full_logcat.log"
FILTERED_LOG="$SESSION_DIR/mitra_filtered.log"
META_FILE="$SESSION_DIR/session_info.txt"
DEVICE_FILE="$SESSION_DIR/device_info.txt"

cat > "$META_FILE" <<EOF
MITRA hardware test log session
start: $START_ISO
folder: $SESSION_DIR
adb: $ADB
filter: $FILTER
EOF

echo "Waiting for Android device..."
"$ADB" wait-for-device

"$ADB" devices -l > "$SESSION_DIR/adb_devices.txt" || true
{
    echo "Device properties"
    echo "captured_at=$(date '+%Y-%m-%d %H:%M:%S %Z')"
    echo "serial=$("$ADB" get-serialno 2>/dev/null || true)"
    echo "model=$("$ADB" shell getprop ro.product.model 2>/dev/null | tr -d '\r' || true)"
    echo "brand=$("$ADB" shell getprop ro.product.brand 2>/dev/null | tr -d '\r' || true)"
    echo "android=$("$ADB" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r' || true)"
    echo "sdk=$("$ADB" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r' || true)"
} > "$DEVICE_FILE"

echo "Clearing old logcat..."
"$ADB" logcat -c || true

full_pid=""
filtered_pid=""

finish() {
    local end_epoch
    end_epoch="$(date +%s)"
    {
        echo "end: $(date '+%Y-%m-%d %H:%M:%S %Z')"
        echo "duration_seconds=$((end_epoch - START_EPOCH))"
        echo "full_log=$FULL_LOG"
        echo "filtered_log=$FILTERED_LOG"
    } >> "$META_FILE"

    [[ -n "${full_pid:-}" ]] && kill "$full_pid" >/dev/null 2>&1 || true
    [[ -n "${filtered_pid:-}" ]] && kill "$filtered_pid" >/dev/null 2>&1 || true

    echo
    echo "Saved MITRA logs in:"
    echo "$SESSION_DIR"
}

trap finish EXIT INT TERM

echo "Saving full logcat to: $FULL_LOG"
"$ADB" logcat -v time > "$FULL_LOG" &
full_pid="$!"

echo "Saving filtered MITRA log to: $FILTERED_LOG"
"$ADB" logcat -v time | awk -v re="$FILTER" '$0 ~ re { print; fflush() }' > "$FILTERED_LOG" &
filtered_pid="$!"

echo
echo "Now test the app. Press Ctrl+C here after you finish, or disconnect the phone."
echo "Session folder: $SESSION_DIR"

wait "$filtered_pid"
