# Architecture

How Bekon Suite pieces connect. Why it exists: [`USE-CASES.md`](USE-CASES.md). Wire format: [`PROTOCOL.md`](PROTOCOL.md).

---

## Layered view

```
┌──────────────────────────────────────────────────────────────────┐
│  apps/desktop-ui (Vue)                                           │
│    /#/tunnels  →  wlya-tunnel REST                               │
│    /#/phone    →  phone-control-api HTTP  (+ phone-control-mcp)    │
│    Voice tab   →  bekon-call WebSocket                           │
└────────────┬─────────────────────┬──────────────────┬────────────┘
             │                     │                  │
             ▼                     ▼                  ▼
     apps/wlya-tunnel          apps/phone-control-api      packages/bekon-call
        :18080                    :18082              (client lib)
             │                     │                  │
             │    polls wlya-tunnel for tunnel list │
             └──────────┬──────────┘                  │
                        ▼                             │
              packages/wlya-core                        │
              + wlya-adapters                         │
                        │                             │
         ┌──────────────┴──────────────┐              │
         ▼                             ▼              ▼
   apps/android-gateway APK              other JVM peers   packages/wlya-server
   (Bekon Gateway)                                    :18081 default
   pro.potoki.bekon                                   Redis + HTTP/WS
         │                                                  │
         │  wlyaserver adapter ──POST/GET /v1/messages───────┤
         │                                                  │
         └──────── tunnel encrypted blobs ─────────────────┘
                                                          │
                              /v1/call WebSocket ◄────────┘
                                    ▲
                    apps/android-phone (Bekon Phone) + desktop-ui Voice tab
```

---

## wlya-core and adapters

**`packages/wlya-core`** is the Kotlin/JVM tunnel runtime: config persistence, AES-GCM message framing, adapter lifecycle, duty coordinator, and seq/id dedup.

**`packages/wlya-adapters`** are compile-time plug-ins. Each adapter implements transport in/out; `wlyaserver` talks to the public relay. Codegen shares form UI between desktop (Vue) and Gateway (`SetupActivity`).

### Adapter duty

Native adapters: **wlya (`wlyaserver`) is primary**; other types default to **backup**.

- Effective primary (first running `role=primary`, else first backup) **never sleeps**: normal poll and send.
- Backup sleeps by default: poll `sleepPollMs ± sleepJitterMs` (~one hour), **no send**.
- Foreign inbound on that backup (after `seenIds`, not echo of our send) → backup becomes **active**.
- Active until `idleMs` (default 10 min) since last foreign inbound. Empty polls do not sleep it. Our own sends do not extend idle.
- `poll FAILED` on the effective primary → all running backups go active immediately. When primary is healthy again and backup idle expires, backup sleeps.

Form section **Polling / role**: `role`, `pollIntervalMs`, `sleepPollMs`, `sleepJitterMs`, `idleMs`. Coordinator: `AdapterDutyCoordinator`. UI: badges and countdown.

Typical case: relay blocked, you send via email from desktop → within about an hour the phone wakes email → that channel runs at full speed while foreign packets continue.

Lua script adapters (Telegram, Sheets, stego-email without a new APK) are planned — [`USE-CASES.md`](USE-CASES.md).

### Channel id vs payload secret

| Concept | Code / JSON | UI label | Used for |
|---------|-------------|----------|----------|
| Channel id | `channel` (legacy JSON key `seed`) | Secret (room id for tunnel) | HMAC auth to relay; Redis namespace (`sha256(channel)`); default AES key if secret blank |
| Payload secret | `secret` | Secret (encryption key) | AES-GCM for tunnel message bodies; never sent to `wlya-server` |

`TunnelConfig.cryptoSecret()` returns `secret.ifBlank { channel }`. The relay stores only opaque base64 blobs — it cannot decrypt tunnel content.

---

## wlya-tunnel (`:18080`)

JVM tunnel host for local dev and desktop setups. REST API for tunnel CRUD, adapter config, message log, and start/stop. The desktop-ui `/#/tunnels` tab proxies `/api` here.

Gradle module: `apps/wlya-tunnel` (Kotlin package `com.wlya.desktop`). Depends on `wlya-core` and generated adapter bindings. State file: `.wlya/wlya-tunnel.json`.

---

## wlya-server (relay)

**`packages/wlya-server`** — Node.js + Redis, typically via `docker compose up` in that directory.

| Endpoint | Role |
|----------|------|
| `GET/POST /v1/messages` | HTTP inbox — poll or push encrypted tunnel blobs |
| `GET /v1/stream` | SSE push for new messages |
| `GET /v1/call` | WebSocket voice rooms (binary PCM frames) |
| `GET /health`, `/metrics` | ops |

Default listen `18081`. Clients authenticate with HMAC headers or signed query string — see [`PROTOCOL.md`](PROTOCOL.md).

Deploy your own instance; example hostnames in docs are illustrations, not a required cloud.

---

## phone-control-api and phone-control-mcp

**`apps/phone-control-api`** (`:18082`) sits between the desktop-ui Control tab and a running tunnel. It does not talk to ADB directly: it enqueues gesture/screenshot commands, `wlya-tunnel` forwards them through the tunnel to the Gateway APK, and results return on the same channel.

**`apps/phone-control-mcp`** (`:18083/mcp`) exposes a compact MCP tool surface over phone-control-api for agents (look, tap, nav, …). Spec: [`CONTROL-PROTOCOL.md`](CONTROL-PROTOCOL.md).

Env: `WLYA_TUNNEL_URL` (desktop base), `PORT`, `HOST`.

---

## Gateway APK vs Bekon Phone

Both are Android apps built from this monorepo; they target different roles.

| | **Gateway** (`apps/android-gateway`) | **Bekon Phone** (`apps/android-phone`) |
|--|------------------------------|--------------------------------|
| Package | `pro.potoki.bekon` | `pro.potoki.bekon.phone` (module `bekon-phone`) |
| Primary role | Tunnel endpoint + Control (screen, gestures, a11y) | Line client — voice / GSM bridge |
| Shared code | `wlya-core`, adapters, `bekon-call` | `bekon-call` |
| Deploy script | `apps/android-gateway/scripts/deploy` | `apps/android-phone/scripts/deploy` |
| desktop-ui tab | Tunnels (config) + Control (queue) | Voice (companion) |

One physical phone can run Gateway (tunnel + Control); another device (or profile) may run Bekon Phone for Line. Names: [`BRAND.md`](BRAND.md).

---

## Voice room vs tunnel channel

These are **different namespaces** on the same relay host:

| | Tunnel | Voice (`/v1/call`) |
|--|--------|---------------------|
| Purpose | Encrypted message queue (commands, files, logs) | Real-time audio frames |
| Identity | Channel id (`seed` in protocol) | Room name (e.g. `kitchen`) |
| Auth | HMAC with channel id as key material | HMAC join; `bekon-call` signs `seed` + `ts` |
| Client | `wlyaserver` adapter, all tunnel peers | `WlyaCallClient` in Gateway Voice service and Bekon Phone |
| UI field | Secret (tunnel) | Room + relay URL |

Joining voice does not automatically join a tunnel channel, and vice versa. Configure both in desktop-ui or app settings.

Line audio modes (walkie, acoustic GSM, root ALSA bridge): [`LINE.md`](LINE.md). Optional Magisk module: [`apps/android-gateway/magisk/wlya-voice/`](../apps/android-gateway/magisk/wlya-voice/README.md).

---

## Local dev stack

`npm run stack:start` (`scripts/dev-start.mjs`) brings up:

1. Vite — `apps/desktop-ui`
2. `wlya-tunnel` — `:18080`
3. `phone-control-api` — `:18082`

Relay is started separately (`packages/wlya-server/docker compose` or `scripts/relay`). MCP is optional (`apps/phone-control-mcp/server.py`).

---

## Related docs

- [`USE-CASES.md`](USE-CASES.md) — jobs to be done and roadmap
- [`PROTOCOL.md`](PROTOCOL.md) — headers, query params, HMAC
- [`CONTROL-PROTOCOL.md`](CONTROL-PROTOCOL.md) — Control queue kinds
- [`LINE.md`](LINE.md) — GSM / voice modes
- [`packages/wlya-server/README.md`](../packages/wlya-server/README.md) — relay deploy
- [`packages/wlya-adapters/README.md`](../packages/wlya-adapters/README.md) — new adapter
