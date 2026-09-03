# Bekon Suite · Be Konnected

**WLYA in the wire. Bekon in the room.**

Self-host the relay. You own the endpoint. Example hostnames in docs are illustrations only.

---

## Use cases

Why this exists. More detail and what is still planned: [`docs/USE-CASES.md`](docs/USE-CASES.md).

### 1. Emigrant gateway

Leave home, leave a **rooted Android with a local SIM** at grandma’s (or any trusted address). Call out and take calls **through that handset** — military commissariat, interior ministry, prison service, banks, whoever still only believes a domestic number.

The pipe is **Bekon Line**: GSM audio over your relay, plus the WLYA tunnel so the device stays reachable when ordinary internet does not.

### 2. Give your agent a phone

Plug MCP into an agent and let it **own a real Android**: tap, swipe, launch apps, pay with your card, doomscroll Instagram, sit in settings until something works.

That is **Bekon Control** — Gateway APK + `phone-manager` + `phone-mcp`. The agent does not ADB the device; it queues work through the tunnel.

### 3. Old Android as a smart speaker

Fire Alice and Alexa. Talk to **Hermes** (or any agent) from a dusty phone on the table — walkie / room audio, no cloud speaker required.

Today this is Line walkie + the same tunnel Control uses. Always-on “speaker in the room” is on the roadmap in [`docs/USE-CASES.md`](docs/USE-CASES.md).

### 4. White List Your Ass

**WLYA** = White List Your Ass. Drive the phone through **email**, a **spreadsheet**, **MAX**, or any custom tunnel adapter — including from a parking garage when the “real” internet is gone. Stack as many backup adapters as you want so you do not lose the line home.

Duty already fails over from the fast relay to sleeping backups ([`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md#adapter-duty)). Lua adapters you can add without a new APK are on the roadmap.

---

## Products

| Layer | Name | Role |
|-------|------|------|
| Transport | **WLYA Tunnel** | HMAC channel, adapters, duty failover. **White List Your Ass.** Protocol `seed`; UI **Secret**. |
| Remote UI | **Bekon Control** | Screen, gestures, files, MCP. Full Gateway APK (`pro.potoki.bekon`). |
| GSM / voice | **Bekon Line** | Home SIM as an anchor. Client: **Bekon Phone**. |
| Umbrella | **Bekon Suite** | Wire + device in the room. Tagline: Be Konnected. |

One device, several modes — not four store brands. Names: [`docs/BRAND.md`](docs/BRAND.md).

```
                    ┌─────────────────────────────────────┐
                    │  Laptop — Bekon Workbench           │
                    │  Tunnels · Control · Voice          │
                    │  wlya-desktop :18080                │
                    │  phone-manager :18082               │
                    └──────────────┬──────────────────────┘
                                   │
              tunnel (HMAC)        │        voice room (HMAC join)
                                   │
                    ┌──────────────▼──────────────────────┐
                    │  Your relay — wlya-server           │
                    │  Redis inbox + /v1/call             │
                    └──────────────┬──────────────────────┘
         ┌─────────────────────────┼─────────────────────────┐
         ▼                         ▼                         ▼
   Gateway APK              Bekon Phone                 Other tunnel
   home device              Line handset                peers
```

Tunnel traffic and voice rooms are separate paths on the same relay. See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) and [`docs/PROTOCOL.md`](docs/PROTOCOL.md).

---

## Quick start

```bash
git clone https://github.com/tsol/bekon.git && cd bekon

# JDK 21 for Gradle. Android SDK only if you build APKs.

npm install
cd packages/phone-manager && npm install && cd ../..

npm run dev:start
# workbench + wlya-desktop :18080 + phone-manager :18082

cd packages/wlya-server && docker compose up -d --build
```

Open the workbench URL from dev-start (default `http://127.0.0.1:5173`). Point adapters and voice at **your** relay.

**Android:** `ANDROID_HOME` set, then `./gradlew :android-client:app:assembleDebug` and `./tools/adb/gateway deploy`. Phone client: `./tools/adb/phone deploy`.

---

## Repository map

```
bekon/
├── apps/workbench/     Vue shell — Tunnels / Control / Voice
├── apps/gateway/       Gateway APK (Control + tunnel + Line service)
├── apps/phone/         Bekon Phone — Line client
├── packages/wlya-core/         Kotlin tunnel core
├── packages/wlya-adapters/     transports (wlyaserver, email, …)
├── packages/wlya-desktop/      JVM REST :18080
├── packages/wlya-server/       Node + Redis relay
├── packages/bekon-call/        /v1/call WebSocket client
├── packages/phone-manager/     Control HTTP :18082
├── packages/phone-mcp/         MCP :18083
├── tools/adb/gateway|phone     USB deploy
├── tools/magisk/wlya-voice/    optional root audio (Line mode C)
└── docs/
```

---

## Documentation

| Doc | Contents |
|-----|----------|
| [`docs/USE-CASES.md`](docs/USE-CASES.md) | The three cases plus roadmap |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | How components connect, adapter duty |
| [`docs/PROTOCOL.md`](docs/PROTOCOL.md) | HMAC, seed vs Secret, relay endpoints |
| [`docs/CONTROL-PROTOCOL.md`](docs/CONTROL-PROTOCOL.md) | phone-manager queue API |
| [`docs/LINE.md`](docs/LINE.md) | Voice / GSM modes A/B/C |
| [`docs/BRAND.md`](docs/BRAND.md) | Product names |
| [`packages/wlya-adapters/README.md`](packages/wlya-adapters/README.md) | Adding an adapter |
| [`packages/wlya-server/README.md`](packages/wlya-server/README.md) | Relay deploy |

---

Licensed under [AGPL-3.0-or-later](LICENSE). See [CONTRIBUTING.md](CONTRIBUTING.md) and [SECURITY.md](SECURITY.md).
