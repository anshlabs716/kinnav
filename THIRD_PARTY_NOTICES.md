# Third-Party Notices

KinNav is built with and depends on third-party software. This file lists the
major components and their licenses. It is provided for attribution and is not
legal advice.

## Core

| Component | License | Use |
| --------- | ------- | --- |
| Kotlin | Apache-2.0 | Application language |
| AndroidX / Jetpack (Compose, Room, Lifecycle, Activity) | Apache-2.0 | UI, database, lifecycle |
| Material 3 (Compose) | Apache-2.0 | Design system |
| kotlinx-serialization | Apache-2.0 | JSON serialization |
| kotlinx-coroutines | Apache-2.0 | Async |

## Maps & navigation

| Component | License | Use |
| --------- | ------- | --- |
| MapLibre Native (Android SDK) | BSD-2-Clause | Map rendering |
| OpenStreetMap | ODbL 1.0 | Map data (attribution shown on map) |

## Location sharing (ported from Luogo-FOSS, module `:family`)

| Component | License | Use |
| --------- | ------- | --- |
| Bouncy Castle (`bcprov-jdk18on`) | MIT | Ed25519 signatures for device identity |
| OkHttp | Apache-2.0 | HTTPS / WebSocket transport to the relay |
| ZXing (`core`) | Apache-2.0 | Invite QR codes |
| Room | Apache-2.0 | Local contacts/circles/key storage |

The Ed25519 implementation uses Bouncy Castle because Android Keystore cannot
generate Ed25519 keys below API 33; AES-GCM comes from the platform JCE.

## Relay server (module `:relay`, JVM)

Uses the same Kotlin/OkHttp/serialization stack as the app. It forwards
ciphertext only and holds no keys.

## Notes

- No Google Play Services, Firebase, or other proprietary SDKs are included.
- Full license texts are distributed with the respective projects.
- If a dependency is listed incorrectly, please open an issue so it can be
  corrected.
- This project's own license is stated in the repository `LICENSE` file (GPLv3).
