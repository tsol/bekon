# Bekon Suite · Be Konnected

**WLYA in the wire. Bekon in the room.**

One monorepo for a resilient tunnel, a device gateway at home, and a GSM voice anchor. Self-host the relay — you control the endpoint. Example deployments may use `wlya.potoki.pro` as an illustration only; production needs your own `wlya-server` instance.

Canonical naming: [`docs/BRAND.md`](docs/BRAND.md)

---

## Products

### WLYA Tunnel

The transport layer: encrypted message channel with HMAC auth, plug-in adapters (email, HTTP relay, mock, …), and duty failover. WLYA is the protocol and UI name — a wire that stays up when ordinary paths do not. Desktop JVM, Android Gateway, and adapters all share `wlya-core`.

### Bekon Control

Full device gateway for agents: screen capture, accessibility tree, gestures, file push, and MCP tools over `phone-manager`. Distributed as the GitHub / full Gateway APK (`pro.potoki.bekon`) plus the workbench Control tab. Not the trimmed Play build — see [`docs/BRAND.md`](docs/BRAND.md).

### Bekon Line

GSM anchor at home: a rooted phone with a SIM in your country, bridging voice and walkie-talkie audio over WebSocket. The desktop Voice tab and **Bekon Phone** client join the same room on your relay. Dial and acoustic modes are documented in [`docs/line.md`](docs/line.md).

---

## How it fits together

```
                    ┌─────────────────────────────────────┐
                    │  Your laptop — Bekon Workbench      │
                    │  Tunnels · Control · Voice tabs     │
                    │  wlya-desktop :18080              │
                    │  phone-manager :18082             │
                    └──────────────┬──────────────────────┘
                                   │
              tunnel (HMAC)        │        voice room (HMAC join)
              wlyaserver adapter   │        /v1/call WebSocket
                                   │
                    ┌──────────────▼──────────────────────┐
                    │  WLYA relay — wlya-server         │
                    │  Redis HTTP inbox + /v1/call        │
                    │  (docker compose, your domain)    │
                    └──────────────┬──────────────────────┘
                                   │
         ┌─────────────────────────┼─────────────────────────┐
         │                         │                         │
         ▼                         ▼                         ▼
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│ Gateway APK     │     │ Bekon Phone     │     │ Other tunnel    │
│ at home         │     │ (Line client)   │     │ peers (desktop) │
│ tunnel + Control│     │ voice / GSM     │     │                 │
│ pro.potoki.bekon│     │                 │     │                 │
└─────────────────┘     └─────────────────┘     └─────────────────┘
   phone on the table       handset / second        laptop elsewhere
```

Tunnel traffic and voice rooms are separate paths on the same relay. Tunnel uses the channel id (`seed` in protocol, **Secret** in UI) for HMAC and AES. Voice uses HMAC join plus a **room** name — see [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) and [`docs/PROTOCOL.md`](docs/PROTOCOL.md).

---

## Quick start

```bash
git clone <your-fork-or-mirror> bekon && cd bekon

# JDK 21 required for Gradle (wlya-core, wlya-desktop, Android)
# Android SDK optional — only for APK builds

npm install
cd packages/phone-manager && npm install && cd ../..

# Workbench + wlya-desktop :18080 + phone-manager :18082
npm run dev:start
# or foreground Vite only: npm run dev

# Self-hosted relay (separate terminal)
cd packages/wlya-server && docker compose up -d --build
```

Open the workbench URL printed by dev-start (default `http://127.0.0.1:5173`). Configure tunnel adapters and voice URL to point at your relay.

**Android (optional):** set `ANDROID_HOME`, then `./gradlew :android-client:app:assembleDebug` and deploy with `./tools/adb/gateway deploy`.

---

## Repository map

```
bekon/
├── apps/
│   ├── workbench/          # Vue 3 shell — Tunnels / Control / Voice
│   ├── gateway/            # Bekon Gateway APK (pro.potoki.bekon)
│   └── phone/              # Bekon Phone — Line voice client
├── packages/
│   ├── wlya-core/          # Kotlin/JVM tunnel core
│   ├── wlya-adapters/      # plug-in transports (wlyaserver, email, …)
│   ├── wlya-desktop/       # JVM REST API :18080
│   ├── wlya-server/        # Node.js + Redis relay (Docker)
│   ├── bekon-call/         # shared /v1/call WebSocket client
│   ├── phone-manager/      # phone-control HTTP API :18082
│   └── phone-mcp/          # MCP tools :18083 over phone-manager
├── tools/
│   ├── adb/gateway         # build / deploy Gateway APK
│   ├── adb/phone           # build / deploy Bekon Phone
│   └── magisk/wlya-voice/  # optional root audio bridge for Line
├── scripts/                # dev-start, dev-stop, udev
├── docs/                   # architecture, protocol, control, line
├── LICENSE                 # AGPL-3.0-or-later
├── CONTRIBUTING.md
└── SECURITY.md
```

---

## Documentation

| Doc | Contents |
|-----|----------|
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | How components connect |
| [`docs/PROTOCOL.md`](docs/PROTOCOL.md) | HMAC, seed vs secret, relay endpoints |
| [`docs/control-protocol.md`](docs/control-protocol.md) | phone-manager queue API (Control) |
| [`docs/line.md`](docs/line.md) | Voice / GSM bridge (Line) |
| [`docs/BRAND.md`](docs/BRAND.md) | Naming, Play vs GitHub APK |
| [`packages/wlya-adapters/README.md`](packages/wlya-adapters/README.md) | Writing a new adapter |

---

## License & community

Licensed under [AGPL-3.0-or-later](LICENSE). See [CONTRIBUTING.md](CONTRIBUTING.md) for build notes and [SECURITY.md](SECURITY.md) to report vulnerabilities privately.

`root-phone/` holds lab device notes (including unpublished brick trees); it is not part of the public product surface.
