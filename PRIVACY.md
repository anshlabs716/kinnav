# Privacy Policy

**KinNav** is designed to be private by default. This policy explains, honestly,
what the app does with your data — including the optional location-sharing
feature inherited from Luogo.

## Short version

- KinNav collects **nothing** and sends **nothing** to a project-operated server.
- There is **no account**, **no analytics**, **no telemetry**, **no advertising ID**.
- Your saved places, search history and settings live **only on your device**.
- Location sharing is **off by default** and only sends data when you turn it on
  for a specific circle. With sharing off and no circles, the sharing subsystem
  makes **no network connections at all**.

## Maps, search and navigation

| Data | Where it goes |
| ---- | ------------- |
| Map tiles | Fetched from the style/tile provider shown in Settings (default: OpenStreetMap / MapLibre). Requests contain your IP and the tile coordinates, as with any map app. |
| Search queries | Sent to the configured search provider (default: OpenStreetMap Nominatim) when you search. |
| Routing requests | Sent to the configured routing provider (default: OSRM / OpenStreetMap-based) with origin and destination. |
| Saved places, history, settings | Stored in a local Room database on your device only. Never uploaded. |

You can use offline maps and on-device data with networking disabled.

## Location sharing (opt-in)

The sharing subsystem (module `:family`, ported from Luogo-FOSS) is the only
part of KinNav that can send your location to anyone, and it does so only when
you explicitly enable it.

### What is shared, and with whom

- You share only with the **circles** you create or join. A circle can be a
  single contact or a group. Each circle has its own share switch.
- A **master switch** gates everything. Turning it off (including the one-tap
  **STOP SHARING NOW** button, the Quick Settings tile, or swiping the app away)
  stops all outbound sharing and disconnects from the relay.
- Nothing is shared on a fresh install. Sharing is never silent.

### How it is protected

- **End-to-end encrypted.** Positions are sealed with AES-256-GCM under a
  per-circle symmetric key. The relay and any network observer see only
  ciphertext.
- **Keys never leave your device except via invites.** Circle keys are
  distributed through invite payloads you choose to share with a specific
  person, sealed so only the intended recipient can open them.
- **Identity is a local Ed25519 keypair.** The private seed never leaves the
  device. There is no account and no phone number / email requirement.
- **Precision control.** You can round your position to a grid (100 m, 500 m,
  1 km, or precise) before encryption; recipients see the rounded point and an
  accuracy that reflects it.

### The relay

- The default relay (`https://relay.luogo.app`) **forwards ciphertext only**.
  It cannot decrypt your location and does not hold your keys.
- You can point KinNav at **your own relay** in Settings → Location sharing.
- The relay's retention is a server-side policy; the client defaults to
  requesting 30 days and you can adjust it.
- Optional **Tor / SOCKS5 routing** is available and, when enabled, is stated
  plainly in diagnostics rather than applied silently.

### What is never shared

- Your saved places, search history, contacts, or any on-device database.
- Anything while sharing is off.
- Your precise position beyond the precision you chose.

## Permissions

- **Location** — for positioning, navigation, and (only if you enable it) sharing.
- **Notifications** — for the navigation and sharing foreground services.
- **Camera** — only if you scan a QR invite code.
- **Boot completed** — only to resume a sharing session you explicitly left on.

## Changes

If this policy changes, the update will be documented in `CHANGELOG.md` and the
relevant source changes are visible in the repository.

## Contact

Open an issue at <https://github.com/anshlabs716/GeoNav> if anything here is
unclear or you believe data is handled differently than described.
