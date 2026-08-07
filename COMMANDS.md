# MITRA Voice Commands

In the **background** (another app in front), say **"MITRA"** first, e.g. "MITRA open WhatsApp".
On the MITRA home screen with voice on, you can skip the wake word.

## Wake / control
- **mitra** (alone) → "Yes, I'm listening"
- **start mitra** → begin device/Wi-Fi search (or phone camera)
- **close** / **go home** / **minimize** / **exit** → home screen
- **go back** / **back** → Back button
- **stop** / **go to sleep** → pause voice (say "mitra" to wake)
- **repeat** / **say again** → repeat last response

## Apps
- **open <app>** / **launch <app>** — e.g. open WhatsApp, open YouTube, open Contacts, open Settings

## Communication
- **call <name>** — e.g. "call Shabaz" (fuzzy name matching)
- **send <message> to <name>** — WhatsApp, e.g. "send I'm on my way to Shabaz"
- **reply <message>** — reply to the last message read aloud

## Media / navigation
- **play <song> on YouTube** — e.g. "play lofi beats on YouTube"
- **navigate to <place>** / **go to <place>** — Google Maps directions

## Vision (needs camera / RTSP frame active)
- **read text** — OCR the current view aloud
- **take picture** / **take photo** / **capture** / **click picture**

## Phone controls
- **flashlight on** / **flashlight off** (also "torch on/off", "light on/off")
- **volume up** / **volume down** · **mute** / **unmute**
- **lock** / **lock screen**
- **screenshot**
- **recent apps** · **notifications** · **quick settings**

## Info
- **what's the time** / **time**
- **what's the date** / **date**
- **battery level** / **battery**

## Conversation
- **hey mitra** / **hi** / **hello** — greeting
- **how are you** · **who are you** · **what can you do** · **thank you** · **good morning**

## Messages (needs Notification access enabled)
- Incoming WhatsApp / SMS are read aloud automatically → then **reply <message>**

---

### Notes / conditions
- Background commands need the **"MITRA"** wake word first.
- "play <song>" must include "YouTube".
- "read text" / "take picture" need an active camera or RTSP frame.
- During a phone call, voice pauses and auto-resumes ~1.5s after the call ends.
- Message reading needs **Notification access**; background app-opening needs the **Accessibility** service on.
