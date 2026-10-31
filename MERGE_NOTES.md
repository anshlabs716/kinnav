# Merge Notes

This repository is the merge of two projects by the same author:

- **[GeoNav](https://github.com/anshlabs716/GeoNav)** — FOSS, privacy-first
  Android maps & navigation (Kotlin, Jetpack Compose, Material 3, MapLibre).
- **[Luogo-FOSS](https://github.com/anshlabs716/luogo-foss)** — FOSS location
  sharing (end-to-end encrypted, relay + LAN, opt-in per contact).

The result is **KinNav**: GeoNav as the base maps/navigation app, with Luogo's
location-sharing engine ported into a new `:family` module and surfaced as a
**People** layer on the map. The repository is kept named `geonav`; the app is
renamed **KinNav**.

This file records **what came from which repo and what changed**, so the
provenance of every subsystem is auditable.

## TL;DR of provenance

| Area | Source | Notes |
| ---- | ------ | ----- |
| Maps, navigation, search, offline, Android Auto, settings, theming | **GeoNav** (`:app`) | Unchanged in structure; extended with the People layer, sharing settings, and a debug diagnostics entry. |
| Sharing UI, crypto, DB, services, relay client | **Luogo** (ported into `:family`) | Package renamed `app.luogo.*` → `org.geonav.family.*`. Trimmed to the sharing subset. |
| Relay protocol engine | **Luogo** (ported into `:shared`) | Package renamed `app.luogo.relay` → `org.geonav.relay`. |
| Standalone relay server | **Luogo** (ported into `:relay`) | Main class `org.geonav.relay.RelayServerKt`; buildable without the Android SDK. |

## Module layout

```
kinnav/
├── app      → :app      GeoNav base (namespace org.geonav.app)
├── family   → :family   Ported sharing (namespace org.geonav.family)   [from Luogo]
├── shared   → :shared   Relay protocol engine (package org.geonav.relay) [from Luogo]
└── relay    → :relay    Standalone relay server                         [from Luogo]
```

`settings.gradle.kts` uses a `-PrelayOnly=true` guard that skips `:app` and
`:family`, so the relay Docker image needs no Android SDK.

## What came from Luogo, and how it was adapted

The port was done from Luogo's **working tree** (uncommitted state), not a
release tag, because the sharing behaviour being preserved was the in-progress
one.

### `:family` (new module; Luogo's `app` sharing code)

- **Crypto** (`crypto/CryptoEngine.kt`) — ported nearly verbatim. Ed25519
  identity (Bouncy Castle), AES-256-GCM per-circle keys, invite build/parse.
  - Invite prefix changed `luogo-invite-key:` → `geonav-invite-key:`; the legacy
    prefix is **still accepted on parse** so codes remain interchangeable on a
    shared relay.
- **Repository** (`repository/FamilyRepository.kt`) — ported from Luogo's
  `LuogoRepository`, reduced to the sharing subset (profile, circles, encrypted
  outbound, inbound decryption, relay lifecycle, diagnostics). Item finding,
  geofences, location history, offline maps and routing were **not** ported —
  GeoNav has its own routing and none are part of "who can see me".
- **Relay client** (`network/RelayClient.kt`) — ported. BLE scanning and
  flush-pending logic trimmed; token cleared on relay URL change; added
  `stopLiveWebSocket()` with a cancellable job so an emergency stop actually
  goes quiet.
- **UI** (`ui/`) — People screen, settings screen, diagnostics screen, and a
  trimmed `FamilyComponents` (subset of Luogo's `CommonComponents`) plus the
  shared spacing scale. Same visuals and spacing as Luogo.
- **Services** (`service/`) — share foreground service, notification helper,
  Quick Settings tile, boot receiver; `LuogoApplication` references replaced
  with the `Family` service locator + `FamilyHooks`.
- **DB** (`db/`) — Room database trimmed to 3 tables (users, groups,
  group_members).

### `:shared` + `:relay` (Luogo's relay engine + server)

- Package renamed `app.luogo.relay` → `org.geonav.relay`.
- `RelaySightingEngineTest` copied to `shared/src/test/kotlin/org/geonav/relay/`.

## What came from GeoNav, and what changed

The GeoNav app is the base. Changes made during the merge:

- **Dependency** on `:family` added to `app/build.gradle.kts`.
- **`GeoNavApplication`** initializes `Family.init(...)` with a `FamilyHooks`
  implementation that bridges GeoNav's `PositioningRepository` fusion state into
  the sharing engine (`FamilyRepository.submitFix`), and contributes host
  diagnostic rows.
- **`MainActivity`** gains a **People** drawer entry/screen and a debug-only
  **Sharing diagnostics** screen.
- **`MapLibreMapView`** gains a **People layer** (per-person coloured, labelled
  dots; stale sightings dimmed; tap → person sheet).
- **`MainMapScreen`** wires the People layer, a layer toggle in the controls bar,
  and a **Person sheet** with a **Navigate here** handoff to
  `RoutingRepository.calculateRoutes`.
- **`SettingsScreen`** gains a **LOCATION SHARING** section (and a debug-only
  diagnostics row).
- **App name** `GeoNav` → `KinNav` in resources and UI strings.
- **CI** workflows bumped JDK 17 → 21 to match the Gradle toolchain.

## Deliberate behaviour changes from Luogo

These are intentional and are the point of the merge, not accidents:

1. **Master sharing switch defaults to OFF.** A fresh install shares nothing.
   Opting in is an explicit tap; sharing is never silent.
2. **The relay socket only runs while sharing is on OR a circle exists.** With
   sharing disabled and no circles, the `:family` module makes **no network
   connections at all** — the app is a plain maps app.
3. **Visibility radius** (new): outbound lat/lon are coarsened onto a grid and
   reported accuracy is widened to match, so recipients' "±" is honest.
4. **Outbound payload now includes a stable `id`** (the parent never sent one),
   so renaming a contact cannot fork it into two entries.
5. **Emergency stop is real**: `RelayClient.stopLiveWebSocket()` cancels the
   reconnect job, so "stop" does not reconnect after a backoff.
6. **Protocol stays wire-compatible** with Luogo: same relay endpoints and
   frames, protocol v1, default relay `https://relay.luogo.app`.

## The `FamilyHooks` seam

`:family` must not reference GeoNav's Application, positioning stack or UI. The
host app implements `FamilyHooks` (`startLocationUpdates()`,
`stopLocationUpdates()`, `diagnosticRows()`) and passes it to `Family.init`.
Everything the sharing engine needs from the outside world flows through this
seam, which is why the module can be compiled out of the UI story entirely and
the app still works as a plain maps/navigation app.

## Not ported (and why)

- **Luogo's "Nearby" Bluetooth radar** — a separate peer-discovery feature over
  BLE/Wi-Fi beacons. Sharing works over LAN + relay without it; porting the
  whole Nearby engine was out of scope for this merge. Left as a follow-up.
- **Luogo's item finding / geofences / history / offline maps / routing** —
  GeoNav has its own versions of these; they are not part of "who can see me".

## Build & test status

- `./gradlew assembleDebug` — builds the KinNav APK.
- `./gradlew test` — 33 unit tests pass (20 crypto in `:family`, 13 relay in
  `:shared`).
- `./gradlew -PrelayOnly=true :relay:installDist` — builds the standalone relay
  server (and is the path the Docker recipe uses).
