# KinNav 🗺️

[![Status](https://img.shields.io/badge/status-early%20development-orange.svg)]()
[![Platform](https://img.shields.io/badge/platform-Android-brightgreen.svg)]()
[![License](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)
[![OpenStreetMap](https://img.shields.io/badge/data-OpenStreetMap-lightgrey.svg)]()
[![MapLibre](https://img.shields.io/badge/maps-MapLibre-blueviolet.svg)]()

**KinNav** is a FOSS, privacy-first Android navigation platform: powerful maps,
turn-by-turn navigation, offline mapping, smart search, deep Android
integration — plus **opt-in location sharing with the people you choose**.

It is the merge of two projects by the same author:

- **GeoNav** — the maps and navigation app (the base you are looking at).
- **Luogo** — the location-sharing engine, ported into KinNav's `:family`
  module. See [`MERGE_NOTES.md`](MERGE_NOTES.md) for exactly what came from
  where and what changed.

## 📑 Table of Contents

- [🚀 What works today](#-what-works-today)
- [👥 Location sharing](#-location-sharing)
- [🧱 Project structure](#-project-structure)
- [🌍 Built With](#-built-with)
- [🔐 Philosophy](#-philosophy)
- [🛠️ Building](#️-building)
- [📜 License](#-license)

## 🚀 What works today

* 🗺️ Worldwide vector maps (MapLibre + OpenStreetMap)
* 🧭 Turn-by-turn navigation (drive / walk / cycle) with route preview
* 📡 Position fusion (GNSS + network + sensors, tunnel dead-reckoning)
* 📴 Offline maps: download, manage and navigate with saved regions
* 🔎 Local and network search with history and saved places
* 🚗 Android Auto navigation
* 📍 GPX / KML / GeoJSON import & export
* 👥 **Location sharing with contacts** (see below)

## 👥 Location sharing

Sharing is a separate, fully opt-in subsystem — the app is a complete maps and
navigation app with sharing switched off, and makes **no network connections of
its own** until you turn it on.

* **Opt-in per contact.** You share only with the circles you create, and each
  circle has its own on/off switch. The master switch starts and stops
  everything; there is a one-tap **STOP SHARING NOW** emergency button.
* **End-to-end encrypted.** Positions are sealed with AES-256-GCM under a
  per-circle key. Keys are exchanged through invites, never sent to the server.
* **LAN + relay.** Circles work over the local network and through an optional
  relay that only ever forwards ciphertext. The default relay is Luogo's; you
  can point KinNav at your own.
* **Precision control.** Round your shared position to a grid (100 m, 500 m,
  1 km, or precise) before it is encrypted; recipients' "±" honestly reflects
  the coarsening.
* **No accounts, no Google.** No Play Services, no Firebase, no sign-up. Your
  identity is an Ed25519 keypair that never leaves the device.
* **People layer on the map.** Contacts who share appear as labelled dots;
  tap one to **Navigate here**.

## 📁 Project Files

```text
kinnav/
├── .editorconfig
├── .gitattributes
├── .github/
├── .gitignore
├── .markdownlint-cli2.jsonc
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   ├── src/
│   │   ├── main/
├── build.gradle.kts
├── CHANGELOG.md
├── CITATION.cff
├── CODE_OF_CONDUCT.md
├── COMPATIBILITY.md
├── CONTRIBUTING.md
├── DATA_SOURCES.md
├── docs/
├── family/
│   ├── build.gradle.kts
│   ├── consumer-rules.pro
│   ├── src/
│   │   ├── main/
│   │   ├── test/
├── fastlane/
├── gradle/
├── gradle.properties
├── gradlew
├── gradlew.bat
├── LICENSE
├── MAP_ATTRIBUTION.md
├── MERGE_NOTES.md
├── metadata/
├── metadata.json
├── PRIVACY.md
├── README.md
├── relay/
│   ├── build.gradle.kts
│   ├── Dockerfile
│   ├── README.md
│   ├── src/
│   │   ├── main/
├── ROADMAP.md
├── SECURITY.md
├── settings.gradle.kts
├── shared/
│   ├── build.gradle.kts
│   ├── src/
│   │   ├── main/
│   │   ├── test/
├── src/
│   ├── assets/
│   │   ├── images/
├── SUPPORT.md
├── THIRD_PARTY_NOTICES.md
```


| Module | What it is |
| ------ | ---------- |
| `:app` | KinNav — maps, navigation, search, settings, the People layer |
| `:family` | Location sharing, ported from Luogo (UI, crypto, DB, services) |
| `:shared` | Relay protocol engine shared by the app and the server |
| `:relay` | Standalone relay server (also built into a Docker image) |

## 🌍 Built With

KinNav is built on open technologies and data sources — **OpenStreetMap**,
**MapLibre**, **Kotlin**, **Jetpack Compose**, **Bouncy Castle** and
**OkHttp** — while keeping the architecture flexible for additional providers
and self-hosted infrastructure. See
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

## 🔐 Philosophy

KinNav gives users control over their maps and their location.

No mandatory account.
No ads.
No unnecessary tracking.
No ecosystem lock-in.
Sharing is always opt-in, per contact, and always stoppable.

See [`PRIVACY.md`](PRIVACY.md) for the full privacy story.

## 🛠️ Building

Requires JDK 21 and the Android SDK (platform 35, build-tools 35).

```bash
./gradlew assembleDebug          # the app
./gradlew test                   # unit tests
./gradlew -PrelayOnly=true :relay:installDist   # relay server, no Android SDK needed
```

The `-PrelayOnly=true` flag skips the Android modules so the relay (and its
Docker image) can be built on a plain JVM machine.

## 📜 License

KinNav is licensed under the **GNU General Public License v3.0**.

See [`LICENSE`](LICENSE) for the full license.

---

> **KinNav — maps without the tracking, shared on your terms.**
