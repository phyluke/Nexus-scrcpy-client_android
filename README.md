# Nexus Scrcpy Client

Minimal, zero-telemetry Android-to-Android scrcpy client over Wireless ADB. Built from scratch in Kotlin with low-latency raw PCM audio streaming and a lightweight floating control bubble.

#Dowload Nexus-scrcpy-client_android app
https://github.com/phyluke/Nexus-scrcpy-client_android/releases/tag/v1.0.0

## Features

- **Android-to-Android Streaming:** View and control a target Android device directly from another Android device over Wireless ADB (`adb connect <ip>:<port>`).
- **Low-Latency Raw PCM Audio:** Streams 48 kHz 16-bit stereo raw PCM audio (`0x726177` / `0x72617720`) directly into Android `AudioTrack` with minimal buffering delay.
- **Hardware Video Decoding:** Uses `MediaCodec` for smooth, low-overhead H.264/video surface rendering.
- **Floating Control Bubble:** Compact collapsible `•••` pill with quick-access `Screen` and `✕ Exit` controls that expands cleanly without obstructing the stream.
- **Minimal & Zero-Telemetry:** Requires only the `INTERNET` permission—no analytics, no background trackers, and no third-party SDK bloat.
- **Automated CI Builds:** GitHub Actions automatically builds and packages `nexus_scrcpy_phyluke.apk` on every push to `main`.

## Architecture & Tech Stack

- **Language:** Kotlin (100%)
- **ADB Transport:** `libadb-android` over TCP/Wireless ADB
- **Video Pipeline:** Android `MediaCodec` + `SurfaceView`
- **Audio Pipeline:** Raw PCM stream (`0x726177` / `0x72617720`) -> `AudioTrack`
- **CI/CD:** GitHub Actions (`.github/workflows`)

## Setup & Usage

1. **Enable Wireless ADB on Host Device:**
   ```bash
   adb tcpip 5555
2.  Both Host and Client Device should be in same Network (Wi-Fi)
3.  Accept ADB key fingerprint on Host device 
