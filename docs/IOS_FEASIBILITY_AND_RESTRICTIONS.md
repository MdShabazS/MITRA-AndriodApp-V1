# MITRA iOS Feasibility And Restrictions

Owner: MdShabazS / Codex  
Status: Planning note before iOS build  
Last updated: 2026-08-17

## Short Decision

MITRA can be built for iOS, but the current Android app cannot be copied directly. iOS needs a separate Swift/Xcode app, a verified iOS stream player path, Core ML or TensorFlow Lite iOS model packaging, and an iOS-specific permission/background design.

## Why Current MITRA May Fail On iOS

| Area | iOS answer | Why it may fail with the current Android/backend design |
|---|---|---|
| App build | Yes | We can build native iOS, but Kotlin/Gradle/Android services do not run on iOS. The app must be rebuilt in Swift/SwiftUI. |
| Hardware WiFi stream | Possible, not guaranteed | Current hardware exposes RTSP/H.264 at `rtsp://10.42.0.1:8554/stream`. iOS native media support is strongest for AVFoundation/HLS; product-grade RTSP may need a third-party native player or a future WebRTC/H.264 hardware path. |
| Backend WebSocket | Yes | iOS can send MessagePack/WebSocket payloads, but the Android frame capture, JPEG packaging, device ID, ACK, and reconnect behavior must be reimplemented and tested. |
| Current AI models | No, not directly | Android target is TFLite assets. iOS product path should convert models to Core ML `.mlmodel`/`.mlpackage`, or explicitly add TensorFlow Lite iOS runtime. Model input/output names, preprocessing, labels, and thresholds must match Android/backend. |
| Detection quality | Not guaranteed | Android hardware tests already showed false or unstable detections for some hazards. iOS will only work well after real MITRA hardware-frame validation, not only successful model conversion. |
| Continuous background running | No, not like Android | iOS normally suspends apps in background. Only declared background modes such as audio, location, VoIP, Bluetooth, external accessory, background fetch/processing, etc. are allowed. Continuous camera/RTSP/AI/listening is restricted. |
| WiFi permission | Yes, with limits | iOS local network access requires a usage description and user approval. Joining/configuring MITRA WiFi requires Hotspot Configuration capability and user approval. The app cannot silently take full WiFi control like an embedded system. |
| Android accessibility actions | No direct equivalent | Android accessibility automation for WhatsApp/send dialogs does not map cleanly to iOS. iOS app-to-app automation is much more restricted. |
| Offline voice commands | Different implementation | Android SpeechRecognizer behavior is OEM-dependent. iOS Speech framework and permissions are different; product-grade parity likely needs an embedded offline fixed-command recognizer shared conceptually across Android/iOS. |

## iOS Restrictions To Design Around

- **Local Network Privacy:** Accessing `10.42.0.1` or discovering local services requires `NSLocalNetworkUsageDescription`; if the user denies it, hardware streaming can fail.
- **WiFi Join Flow:** `NEHotspotConfigurationManager` can configure/join a WiFi network only with the Hotspot Configuration entitlement and user consent.
- **Background Runtime:** iOS does not allow an always-running Android-style foreground service. Long-running stream/inference/listening is reliable only while the app is foreground unless it fits an approved background mode.
- **Streaming Protocol:** Keeping RTSP means we must prove a reliable iOS RTSP decoder. For equal Android+iOS behavior, WebRTC/H.264 remains the stronger long-term product candidate.
- **Model Runtime:** Current backend/Android model assets are not automatically iOS-ready. Convert to Core ML or bundle TFLite iOS, then validate preprocessing and outputs against Android/backend.
- **Automation Limits:** iOS cannot freely open/control other apps the way Android accessibility/service flows can.

## Practical Recommendation

Build an iOS prototype only after locking the shared product contract:

1. Hardware stream contract: H.264 profile, resolution, FPS, GOP/keyframe interval, SPS/PPS behavior, latency proof, and iOS decoder decision.
2. Backend contract: same WebSocket URL shape, MessagePack schema, frame IDs, ACKs, cloud-off behavior, and reconnect behavior.
3. Model contract: one manifest mapping Android TFLite and iOS Core ML assets to the same labels, input size, thresholds, and feature names.
4. iOS permission plan: Local Network, WiFi Hotspot Configuration, Camera/Microphone/Speech, and clear user prompts.
5. Background plan: foreground-first assistive workflow, with only Apple-approved background modes where justified.

## Bottom Line

iOS is feasible, but not identical to Android. Stream preview, cloud Q&A, OCR, and local AI can be rebuilt. Android-only background service behavior, accessibility automation, RTSP assumptions, and current TFLite asset packaging are the main risk areas.

## References

- Apple Local Network usage key: https://developer.apple.com/documentation/BundleResources/Information-Property-List/NSLocalNetworkUsageDescription
- Apple local network privacy technote: https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy
- Apple WiFi configuration overview: https://developer.apple.com/documentation/technotes/tn3111-ios-wifi-api-overview
- Apple Hotspot Configuration Manager: https://developer.apple.com/documentation/networkextension/nehotspotconfigurationmanager
- Apple Background Modes: https://developer.apple.com/documentation/xcode/configuring-background-execution-modes
- Apple Core ML: https://developer.apple.com/documentation/coreml
- MITRA product streaming plan: `docs/PRODUCT_STREAMING_ARCHITECTURE.md`
