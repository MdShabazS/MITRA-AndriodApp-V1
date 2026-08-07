# Frame Metadata Schema and Real Examples

This file documents the metadata payload shape used by `CameraActivity` when sending frame analysis results over WebSocket.

## Payload Shape

A frame message is sent as a MessagePack map with keys such as:

- `type`: string, e.g. `frame_result`
- `deviceId`: string
- `frame`: map with analysis results

### `frame` map contents

- `frameId`: integer
- `tsMs`: integer timestamp in milliseconds
- `dayNight`: string (`DAY` or `NIGHT`)
- `sceneType`: string (`INDOOR`, `OUTDOOR`, or `null`)
- `executedFeatures`: array of feature names run for this frame
- `skippedFeatures`: map of skipped features to skip reason
- `detectionsByFeature`: map from feature name to array of detection objects
- `latenciesMs`: map from feature name to execution time in ms
- `skipReason`: string or `null`

### Detection object

- `label`: string
- `score`: number
- `box`: array of four numbers
  - For object detections: normalized `[left, top, right, bottom]`
  - For OCR: pixel coordinates `[left, top, right, bottom]`

## Real Logged Examples

### Example 1 — Frame 63

```json
{
  "frameId": 63,
  "tsMs": 1685534057049,
  "dayNight": "DAY",
  "sceneType": "INDOOR",
  "executedFeatures": ["SCENE", "FIRE_SMOKE", "WET_DRY"],
  "skippedFeatures": {
    "POTHOLE": "SCENE_INDOOR",
    "ELECTRIC_POLE": "SCENE_INDOOR"
  },
  "detectionsByFeature": {
    "FIRE_SMOKE": [
      {"label": "smoke", "score": 0.55, "box": [0.00, 0.00, 0.98, 0.99]}
    ],
    "WET_DRY": [
      {"label": "wet", "score": 0.28, "box": [0.37, 0.00, 0.52, 0.15]}
    ],
    "SCENE": [
      {"label": "shower_indoor", "score": 9.461, "box": null}
    ]
  },
  "latenciesMs": {
    "SCENE": 45,
    "FIRE_SMOKE": 120,
    "WET_DRY": 98
  },
  "skipReason": null
}
```

### Example 2 — Frame 65

```json
{
  "frameId": 65,
  "tsMs": 1685534059004,
  "dayNight": "DAY",
  "sceneType": "INDOOR",
  "executedFeatures": ["SCENE", "OCR"],
  "skippedFeatures": {
    "FIRE_SMOKE": "FEATURE_TIMEOUT",
    "POTHOLE": "SCENE_INDOOR"
  },
  "detectionsByFeature": {
    "OCR": [
      {"label": "ADY", "score": 1.00, "box": [143.00, 225.00, 232.00, 304.00]}
    ],
    "SCENE": [
      {"label": "hospital_room_indoor", "score": 6.832, "box": null}
    ]
  },
  "latenciesMs": {
    "SCENE": 48,
    "OCR": 90
  },
  "skipReason": null
}
```

### Example 3 — Frame 73

```json
{
  "frameId": 73,
  "tsMs": 1685534067725,
  "dayNight": "DAY",
  "sceneType": "INDOOR",
  "executedFeatures": ["SCENE", "FIRE_SMOKE", "WET_DRY"],
  "skippedFeatures": {
    "POTHOLE": "SCENE_INDOOR",
    "ELECTRIC_POLE": "SCENE_INDOOR"
  },
  "detectionsByFeature": {
    "FIRE_SMOKE": [
      {"label": "fire", "score": 0.25, "box": [0.90, 0.63, 0.95, 0.69]}
    ],
    "WET_DRY": [
      {"label": "wet", "score": 0.69, "box": [0.04, 0.01, 0.97, 1.00]}
    ],
    "SCENE": [
      {"label": "elevator_shaft_indoor", "score": 7.258, "box": null}
    ]
  },
  "latenciesMs": {
    "SCENE": 46,
    "FIRE_SMOKE": 110,
    "WET_DRY": 100
  },
  "skipReason": null
}
```

## Notes

- Scene results are logged as raw model scores, not normalized confidences.
- OCR box coordinates are pixel values; other detections use normalized coordinates.
- If a feature has no bounding box, `box` can be `null`.

If you want, I can also add an exact `CameraActivity` MessagePack serialization example with the `type` and `deviceId` wrapper. 
