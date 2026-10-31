# Luogo Relay

A zero-knowledge end-to-end encrypted relay for **Luogo-FOSS**, in Kotlin.

The relay stores and forwards only **ciphertext** that clients produced. It holds no group
keys, so a compromise does not reveal anyone's location. What it *does* enforce is
**authorisation**: whether a sender is a member of a group, and whether an invite is still
valid and unused.

Confidentiality is the client's job (AES-256-GCM under a per-group key); authorisation is the
server's.

> Encryption note: payloads are sealed with **AES-256-GCM**, with `Ed25519` for device identity
> and `HMAC-SHA256` for keyed derivation. Earlier documentation claimed ChaCha20-Poly1305, which
> is not what the client implements.

## Modules

| Module    | Contents |
| --------- | -------- |
| `:shared` | The relay engine: users, tokens, groups, invites, message log, sightings. Shared with the Android app so both enforce identical rules. |
| `:relay`  | The HTTP and WebSocket server. |

The engine previously existed as two independent copies — one tested inside the app, one
untested inside a single server file. They had already drifted: the server had grown a stub
serving only `/api/health`, `/api/users` and `/api/groups`. There is now one implementation.

## Build and run

```bash
./gradlew :relay:installDist
relay/build/install/relay/bin/relay -addr :8080 -max-log 500
```

## Docker

```bash
docker build -f relay/Dockerfile -t luogo-relay .
docker run -p 8080:8080 luogo-relay -addr :8080
```

## API

All endpoints are `application/json`. Everything except `/api/health`, `/api/users` and the
WebSocket upgrade requires `Authorization: Bearer <token>`.

| Method | Path | Purpose |
| ------ | ---- | ------- |
| GET | `/api/health` | Liveness and protocol version. |
| POST | `/api/users` | Register a device. Returns the profile and a bearer token, shown once. |
| GET | `/api/groups` | Groups the caller belongs to. |
| POST | `/api/groups` | Create a group. The caller becomes its owner. |
| POST | `/api/groups/{id}/invites` | Mint a **single-use** invite token. |
| POST | `/api/groups/{id}/join` | Redeem an invite. A second use of the same token is rejected. |
| POST | `/api/groups/{id}/messages` | Append opaque ciphertext. Members only. |
| GET | `/api/groups/{id}/messages?after=N` | Fetch ciphertext after sequence `N`. Members only. |
| DELETE | `/api/groups/{id}/members?userId=` | Remove a member. Owner only. |
| POST | `/api/sightings` | Submit a crowdsourced BLE sighting. Duplicate report ids are rejected. |
| GET | `/api/items/{rotatingId}/sighting` | Most recent sighting for an item. Payload stays ciphertext. |
| GET | `/ws?token=` | WebSocket upgrade. Server pushes `{"type":"message",...}` frames for groups you are in. |

### Rotating identifiers

`sightings` takes a `rotatingBleId`: 32 hex characters (16 bytes). Anything shorter or
non-hex is rejected with `409` rather than stored. This matches the 15-minute rotating
identifier the Android client advertises.

### Message log

The log is bounded per group. When `-max-log` is exceeded the oldest messages are pruned, so
a client that was offline longer than the window catches up with the remainder rather than
the whole history.

## Security notes

- Tokens are stored only as SHA-256 hashes, so a memory dump does not yield usable credentials.
- Invites expire after 7 days and are single use.
- Unauthenticated and non-member requests get `401` and `403` respectively, tested.
- Internal exception messages are logged server-side but never returned to a client.
- **Terminate TLS in front of this process.** Bearer tokens are sent on every request.

## Testing

`app/src/test/kotlin/app/luogo/app/RelaySightingEngineTest.kt` covers the engine as embedded in
the app: sighting storage, duplicate rejection, malformed identifiers, bounded logs,
membership authorisation, and invite single-use.
