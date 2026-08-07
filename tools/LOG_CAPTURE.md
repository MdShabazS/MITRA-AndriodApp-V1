# MITRA Hardware Log Capture

Run this from the project root while the phone is connected:

```bash
./tools/capture_mitra_logs.sh
```

Each run creates a new folder under `LOGS/`, for example:

```text
LOGS/2026-07-28_15-06-00/
```

Inside that folder:

- `mitra_filtered.log` — important MITRA / RTSP / navigation / crash logs
- `full_logcat.log` — complete Logcat for deeper debugging
- `session_info.txt` — start/end time and filter used
- `device_info.txt` — phone model and Android version
- `adb_devices.txt` — connected device details

Stop the capture with `Ctrl+C` after testing. Start the script again for the next hardware connection; it will create a new timestamped folder.
