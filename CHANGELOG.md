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

- App renamed **GeoNav → KinNav** (repository remains `anshlabs716/GeoNav`).
- CI workflows updated to JDK 21 to match the Gradle toolchain.

### Notes

- The app is a complete maps/navigation app with sharing disabled; the sharing
  subsystem makes no network connections until enabled.

<!-- Add new entries above this line, newest first, per release. -->
