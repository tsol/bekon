<div align="center">

<img src="docs/bekon-icon.png" alt="Bekon" width="128">

# Bekon Suite · Be Konnected

**English** · [Русский](README.ru.md) · [Українська](README.uk.md) · [Беларуская](README.be.md) · [中文](README.zh.md) · [فارسی](README.fa.md)

</div>

---

## Use cases

Why this exists. More detail and what is still planned: [`docs/USE-CASES.md`](docs/USE-CASES.md).

### 1. Emigrant gateway

Leave home, leave a **rooted Android with a local SIM** at grandma’s (or any trusted address). Call out and take calls **through that handset** — military commissariat, interior ministry, prison service, banks, whoever still only believes a domestic number.

The pipe is **Bekon Line**: GSM audio over your relay, plus the WLYA tunnel so the device stays reachable when ordinary internet does not.

**Setup guide:** [`docs/guides/GUIDE-LINE.md`](docs/guides/GUIDE-LINE.md)

### 2. Give your agent a phone

Plug MCP into an agent and let it **own a real Android**: tap, swipe, launch apps, pay with your card, doomscroll Instagram, sit in settings until something works.

That is **Bekon Control** — Gateway APK + `phone-control-api` + `phone-control-mcp`. The agent does not ADB the device; it queues work through the tunnel.

**Setup guide:** [`docs/guides/GUIDE-CONTROL.md`](docs/guides/GUIDE-CONTROL.md)

### 3. Old Android as a smart speaker

Fire Alice and Alexa. Talk to **Hermes** (or any agent) from a dusty phone on the table — walkie / room audio, no cloud speaker required.

Today this is Line walkie + the same tunnel Control uses. Always-on “speaker in the room” is on the roadmap in [`docs/USE-CASES.md`](docs/USE-CASES.md).

**Setup guide:** [`docs/guides/GUIDE-SPEAKER.md`](docs/guides/GUIDE-SPEAKER.md)

### 4. White List Your Ass

**WLYA** = White List Your Ass. Drive the phone through **email**, a **spreadsheet**, **MAX**, or any custom tunnel adapter — including from a parking garage when the “real” internet is gone. Stack as many backup adapters as you want so you do not lose the line home.

Duty already fails over from the fast relay to sleeping backups ([`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md#adapter-duty)). Lua adapters you can add without a new APK are on the roadmap.

**Setup guide:** [`docs/guides/GUIDE-WLYA.md`](docs/guides/GUIDE-WLYA.md)

---

## Products

| Layer | Name | Role |
|-------|------|------|
| Transport | **WLYA Tunnel** | HMAC channel, adapters, duty failover. **White List Your Ass.** Protocol `seed`; UI **Secret**. |
| Remote UI | **Bekon Control** | Screen, gestures, files, MCP. Full Gateway APK (`pro.potoki.bekon`). |
| GSM / voice | **Bekon Line** | Home SIM as an anchor. Client: **Bekon Phone**. |
| Umbrella | **Bekon Suite** | Wire + device in the room. Tagline: Be Konnected. |

**Line (voice)**

```
              ┌───────────────────────────┐
              │ phone in your pocket,     │
              │ you in Georgia            │
              └─────────────┬─────────────┘
                            │
              ┌─────────────▼─────────────┐
              │ wlya relay                │
              │ (WebSocket mirror)        │
              └─────────────┬─────────────┘
                            │
              ┌─────────────▼─────────────┐      ┌─────┐
              │ grandma’s old Android     │ ──→  │ GSM │
              └───────────────────────────┘      └─────┘
```

**Control (agent)**

```
        agent ──→ ┌───────────┐ ←── or you control it yourself
                  │ phone-mcp │
                  └─────┬─────┘
                        │
              ┌─────────▼─────────┐
              │ imap, smtp, wlya —│
              │ any channel       │
              └─────────┬─────────┘
                        │
              ┌─────────▼─────────┐
              │ grandma’s old     │
              │ Android           │
              └───────────────────┘
```

Wire details: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md), [`docs/PROTOCOL.md`](docs/PROTOCOL.md).

---

## Quick start

```bash
git clone https://github.com/tsol/bekon.git && cd bekon

npm run install:all
npm run demo              # wizard: relay + stack + demo channel/secret
# or manually:
npm run relay:compose     # local Redis + relay
npm run stack:start       # desktop-ui + wlya-tunnel :18080 + phone-control-api :18082
npm run gateway:deploy    # USB — needs ANDROID_HOME / adb
```

Open the UI URL from `npm run stack:status` (often `http://127.0.0.1:5174`). Point adapters and voice at **your** relay.

**All commands:** [`docs/COMMANDS.md`](docs/COMMANDS.md). **Per use case:** [`docs/guides/GUIDES.md`](docs/guides/GUIDES.md).

**Android (aliases):** `npm run gateway:build`, `npm run phone-app:deploy`.

---

## Repository map

```
bekon/
├── apps/desktop-ui/          Vue shell — Tunnels / Control / Voice
├── apps/wlya-tunnel/       JVM tunnel host REST :18080
├── apps/phone-control-api/ Control HTTP :18082
├── apps/phone-control-mcp/   MCP :18083
├── apps/android-gateway/             Gateway APK + magisk module + deploy script
├── apps/android-phone/               Bekon Phone — Line client + deploy script
├── packages/wlya-core/       Kotlin tunnel library
├── packages/wlya-adapters/   transports (wlyaserver, email, …)
├── packages/wlya-server/     Node + Redis relay
├── packages/bekon-call/      /v1/call WebSocket client
├── scripts/                    monorepo dev (stack:start, stack:stop)
├── tools/                      run.sh, demo wizard
└── docs/
```

---

## Documentation

| Doc | Contents |
|-----|----------|
| [`docs/USE-CASES.md`](docs/USE-CASES.md) | The four cases plus roadmap |
| [`docs/guides/GUIDES.md`](docs/guides/GUIDES.md) | Setup guides (Line, Control, Speaker, WLYA) |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | How components connect, adapter duty |
| [`docs/PROTOCOL.md`](docs/PROTOCOL.md) | HMAC, seed vs Secret, relay endpoints |
| [`docs/CONTROL-PROTOCOL.md`](docs/CONTROL-PROTOCOL.md) | phone-control-api queue API |
| [`docs/LINE.md`](docs/LINE.md) | Voice / GSM modes A/B/C |
| [`docs/COMMANDS.md`](docs/COMMANDS.md) | npm scripts — build, deploy, relay, lab |
| [`packages/wlya-adapters/README.md`](packages/wlya-adapters/README.md) | Adding an adapter |
| [`packages/wlya-server/README.md`](packages/wlya-server/README.md) | Relay deploy |

---

Licensed under [AGPL-3.0-or-later](LICENSE). See [CONTRIBUTING.md](CONTRIBUTING.md) and [SECURITY.md](SECURITY.md).
