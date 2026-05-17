# Saving

A privacy-first super app — no phone number, 4-byte user IDs, social recovery.

## Screenshots

<p float="left">
  <img src="screenshots/IMG_3332.PNG" width="160" alt="Login">
  <img src="screenshots/IMG_3333.PNG" width="160" alt="Enter ID">
  <img src="screenshots/IMG_3334.PNG" width="160" alt="Home">
  <img src="screenshots/IMG_3335.PNG" width="160" alt="Transfer">
</p>
<p float="left">
  <img src="screenshots/IMG_3336.PNG" width="160" alt="History">
  <img src="screenshots/IMG_3337.PNG" width="160" alt="QR Scan">
  <img src="screenshots/IMG_3339.PNG" width="160" alt="Cards">
  <img src="screenshots/IMG_3340.PNG" width="160" alt="Add Card">
</p>

## Architecture

```
iPhone (iOS 17+)
  │  Wire TCP (binary)       HTTP/JSON
  ├──────────────────▶ saving (Wire server)
  ├──────────────────▶ Merchants  :8090
  └──────────────────▶ Cards      :8091
                              │
                         RabbitMQ
                              │
                        TopupWorker
                              │
                         Wire TCP
```

| Component | Stack | Port |
|-----------|-------|------|
| **saving** | C + PostgreSQL | 7474 TCP |
| **Merchants** | Go | 8090 |
| **Cards** | Go + SQLite | 8091 |
| **TopupWorker** | Go | — (AMQP consumer) |
| **Tomcats** | Go | — (notification service, APNs + FCM) |
| **saving-ios** | Swift / SwiftUI | iOS 17+ |
| **wire** | Xcode host app | — |
| **wire-android** | Kotlin / Jetpack Compose | Android 8+ (API 26) |
| **java** | Java / Undertow + PostgreSQL | server-side wallet |

## Wire Protocol

Binary TCP — `len(4 BE) | type(1) | seq(4 BE) | body | HMAC-SHA256(32)`

- 4-byte user IDs, VIP range `uid < 16,777,216` (reserved)
- Session token: 32 bytes returned on login
- Transfer body: `token(32) | toUID(4 BE) | amount(8 BE)`
- Auth: HMAC-SHA256 over entire frame with shared secret

## Features

- **Login / Register** — 4-byte ID + password, no phone
- **Transfer** — binary Wire protocol, real-time balance
- **QR Pay** — scan merchant QR → verify Ed25519 signature → pay → confirm order
- **Card top-up** — async via RabbitMQ → TopupWorker → Wire credit
- **Push notifications** — APNs on topup done
- **Social recovery** — guardian-based account recovery

## Running locally

**Prerequisites:** PostgreSQL, RabbitMQ, Go 1.21+, Xcode 15+

```bash
# Start Wire server
cd saving && ./saving

# Start Go services
cd Merchants && ./merchants
cd Cards && ./cards
cd TopupWorker && FLOAT_PWD=saving_float_changeme ./topup-worker

# Or all at once
./dev.sh
```

**iOS:** Open `wire/wire.xcodeproj` in Xcode, set your team, run on device.

The app defaults to `127.0.0.1` (Simulator). For a real device, edit `macIP` in `wire/wire/wireApp.swift` to your Mac's LAN IP.

**Android:** Open `wire-android/` in Android Studio, edit the backend host in `WireApp.kt`, run on device or emulator (API 26+).

## Environment variables

| Service | Var | Default |
|---------|-----|---------|
| TopupWorker | `WIRE_HOST` | `127.0.0.1` |
| TopupWorker | `WIRE_SECRET` | `saving_wire_secret_changeme` |
| TopupWorker | `FLOAT_PWD` | — |
| TopupWorker | `AMQP_URL` | `amqp://guest:guest@localhost:5672/` |
| Cards | `WIRE_TOKEN` | `change-me-in-production` |
| Cards | `APNS_KEY_ID` | — (push disabled if unset) |
| Cards | `APNS_TEAM_ID` | — |
| Cards | `APNS_BUNDLE_ID` | — |
| Cards | `APNS_KEY_PATH` | — |

## Float account

The top-up worker draws from a VIP float account (`uid=1`). Create it once:

```sql
INSERT INTO accounts (id, password_hash, balance, created_at)
VALUES (1, decode('<sha256_of_password>', 'hex'), 999999999999, now());
```
