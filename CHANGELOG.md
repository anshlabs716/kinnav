# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] — 2026-10-30

First release of **KinNav**, the merge of GeoNav (maps & navigation) and Luogo
(location sharing).

### Added

- **Location sharing** (module `:family`, ported from Luogo-FOSS):
  - Opt-in per contact/circle, with a master switch that defaults to **off**.
  - End-to-end encryption (AES-256-GCM per-circle keys, Ed25519 device identity
    via Bouncy Castle).
  - Invite keys/QR codes for exchanging circle keys; legacy `luogo-invite-key:`
    prefixes still accepted for wire compatibility.
  - Visibility radius (round shared position to a grid) and emergency
    **STOP SHARING NOW**.
  - Foreground share service, Quick Settings tile, and boot-resume receiver.
  - Relay transport with optional Tor/SOCKS5 routing; defaults to the Luogo
    relay, self-hostable.
- **People layer** on the map: contacts who share appear as labelled dots with a
  per-person tap → **Navigate here** handoff to routing.
- **People screen**: circles, sightings, invites, sharing controls.
- **Sharing settings**: identity, precision, relay, diagnostics.
- **Debug-only NERDY diagnostics** screen for the sharing engine.
- `:shared` + `:relay`: relay protocol engine and standalone server (Docker-ready
  via `-PrelayOnly=true`).

### Changed

- App renamed **GeoNav → KinNav** (repository is now `anshlabs716/kinnav`).
- CI workflows updated to JDK 21 to match the Gradle toolchain.

### Notes

- The app is a complete maps/navigation app with sharing disabled; the sharing
  subsystem makes no network connections until enabled.

## [Unreleased]

### Fixed

- **Location sharing now works end to end through the relay.** Circle creation,
  invite redemption and leaving a circle are wired to the relay's real
  endpoints; previously circles existed only on-device, so every published
  ciphertext was rejected and no contact could ever see a position. Invites are
  no longer minted with a fake local token — if the relay cannot issue one, the
  app says so instead of producing an invite that could never be redeemed.
- **Turn-by-turn guidance is live.** The guidance engine is wired into the map:
  maneuver cards advance with real GPS fixes, remaining distance/ETA update
  during navigation, arrival is detected and announced, deviation triggers a
  cooldown-guarded reroute, and the voice-guidance setting now drives a real
  Android TTS engine (it previously had no TTS at all).
- **Offline regions are real offline packs.** Downloads now go through MapLibre's
  native offline pack database — the same store the renderer reads — so a region
  marked Downloaded genuinely renders without network under the style it was
  downloaded with (zoom levels 8–15). Progress, sizes and deletion are all wired
  to the real pack status; the catalogue syncs with reality on every screen open.
  The fictional "offline server URL" setting that no longer serves a purpose was
  removed.
- **Item finding (new feature).** Keys, bikes and luggage can be marked lost and
  advertised over BLE under an hourly-rotating pseudonym (HMAC-SHA256 of a
  device-only key). Other KinNav phones with "help others find" enabled report
  beacon sightings — rotating id, position and RSSI only — to the relay, which
  cannot relate two sightings without the owner's key. Owners locate items by
  querying the relay and decrypt-verifying nothing but their own ids. Inbound
  payload parsing was hardened from regex scraping to strict JSON with sanity
  bounds, and stale peers are pruned after the retention window.
- **Search history works.** Searches are saved when a result is picked
  (move-to-top, no duplicates) and recent searches appear under the search bar;
  Settings' "Clear Search History" now clears something that existed.
- **Export produces a file you can actually open** (SAF document picker)
  instead of writing into app-private storage, and **import is wired**: GPX,
  KML, GeoJSON and CSV files can be read back into Saved Places with
  duplicate skipping.
- **True North and speed-based auto zoom settings are wired** to the compass
  fusion and the map camera respectively (both were stored-but-ignored).
- `POST_NOTIFICATIONS` is now requested at runtime on Android 13+, so sharing
  and navigation notifications are not silently dropped.
- Branding: user-facing text, GPX metadata and HTTP User-Agent now say KinNav;
  repository references point at `anshlabs716/kinnav`.
- `ci.yml` is a real workflow (relay engine tests + server build + live smoke
  test) instead of an `echo` stub.

### Added

- Unit tests for the guidance engine (`TurnByTurnEngineTest`, 8 cases) — the
  `:app` module previously had none.
- **Item finding** (`org.geonav.family.finding`): rotating-id BLE beacons, a
  sightings client for the relay's existing `/api/sightings` endpoints, lost
  mode, helper scanning and a "Find items" section on the People screen —
  covered by new unit tests for the crypto, the item codec and the hardened
  payload parser.

<!-- Add new entries above this line, newest first, per release. -->
