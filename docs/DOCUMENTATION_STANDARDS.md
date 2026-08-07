# MITRA Documentation Standards

This repository is used by app, AI, and hardware team members. Every meaningful change must leave a readable trail in Markdown so another teammate can understand what changed, why it changed, how it was tested, and what hardware or AI assumptions were affected.

## Required Rule

Every code, model, workflow, hardware-contract, or behavior change must include documentation in the same branch and preferably the same commit.

Do not treat documentation as optional cleanup. If the app behavior changes and the docs do not explain it, the work is incomplete.

## What To Update

| Change type | Required documentation |
|---|---|
| User-visible app flow | `README.md` and `WORKFLOW.md` |
| Voice commands or assistant behavior | `COMMANDS.md` |
| Hardware WiFi, RTSP, camera, or streaming assumptions | `docs/HARDWARE_INTEGRATION.md` and `WORKFLOW.md` |
| Local AI, TFLite models, hazard engine, frame pipeline | `README.md`, `WORKFLOW.md`, `STREAMING_METADATA_SCHEMA.md` when data shape changes |
| Developer workflow, build, test, release steps | `README.md`, `WORKFLOW.md`, or a new file under `docs/` |
| Architecture decisions or constraints for future Codex work | `AGENTS.md` |
| Completed task summary | `docs/WORK_LOG.md` |

## Work Log Format

Add one entry to `docs/WORK_LOG.md` for each finished task.

Each entry should include:

- Date
- Author or owner
- Summary
- Files changed
- App impact
- Hardware impact
- AI/model impact
- Validation performed
- Follow-ups or risks

Keep entries short but specific. A teammate should be able to scan the log and understand the project history without reading every commit.

## Commit Expectations

- Use small, logical commits.
- Mention documentation updates in the commit message when docs are part of the change.
- Do not push to the organization repo directly from this workspace.
- Work in the personal repo first: `MdShabazS/MITRA-AndriodApp-V1`.
- Keep the organization repo as a final upstream target only after review.

## Hardware Handoff Expectations

When a change affects the hardware engineer, document:

- Required WiFi SSID, password behavior, IP, ports, and stream path
- Expected frame rate or latency assumptions
- Any packet, JSON, MessagePack, RTSP, or model metadata contract
- What the Android app expects from the device
- What the hardware/firmware can safely change without breaking the app

Use `docs/HARDWARE_INTEGRATION.md` as the hardware-facing source of truth.
