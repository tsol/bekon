# Architecture

How Bekon Suite pieces connect. Naming rules: [`BRAND.md`](BRAND.md). Wire format: [`PROTOCOL.md`](PROTOCOL.md).

---

## Layered view

```
┌──────────────────────────────────────────────────────────────────┐
│  apps/workbench (Vue)                                            │
│    /#/tunnels  →  wlya-desktop REST                              │
│    /#/phone    →  phone-manager HTTP  (+ phone-mcp for agents)   │
│    Voice tab   →  bekon-call WebSocket                           │
└────────────┬─────────────────────┬──────────────────┬────────────┘
             │                     │                  │
             ▼                     ▼                  ▼
   packages/wlya-desktop    packages/phone-manager   packages/bekon-call
        :18080                    :18082              (client lib)
             │                     │                  │
             │    polls wlya-desktop for tunnel list │
             └──────────┬──────────┘                  │
                        ▼                             │
              packages/wlya-core                        │
              + wlya-adapters                         │
                        │                             │
         ┌──────────────┴──────────────┐              │
         ▼                             ▼              ▼
   apps/gateway APK              other JVM peers   packages/wlya-server
   (Bekon Gateway)                                    :18081 default
   pro.potoki.bekon                                   Redis + HTTP/WS
         │                                                  │
         │  wlyaserver adapter ──POST/GET /v1/messages───────┤
         │                                                  │
         └──────── tunnel encrypted blobs ─────────────────┘
                                                          │
                              /v1/call WebSocket ◄────────┘
                                    ▲
                    apps/phone (Bekon Phone) + workbench Voice tab
```

---

## wlya-core and adapters

**`packages/wlya-core`** is the Kotlin/JVM tunnel runtime: config persistence, AES-GCM message framing, adapter lifecycle, duty coordinator, and seq/id dedup.

**`packages/wlya-adapters`** are compile-time plug-ins. Each adapter implements transport in/out; `wlyaserver` talks to the public relay. Codegen shares form UI between desktop (Vue) and Gateway (`SetupActivity`).

### Channel id vs payload secret

| Concept | Code / JSON | UI label | Used for |
|---------|-------------|----------|----------|
| Channel id | `channel` (legacy JSON key `seed`) | Secret (room id for tunnel) | HMAC auth to relay; Redis namespace (`sha256(channel)`); default AES key if secret blank |
| Payload secret | `secret` | Secret (encryption key) | AES-GCM for tunnel message bodies; never sent to `wlya-server` |

`TunnelConfig.cryptoSecret()` returns `secret.ifBlank { channel }`. The relay stores only opaque base64 blobs — it cannot decrypt tunnel content.

---

## wlya-desktop (`:18080`)

JVM service embedded in local dev and production desktop setups. REST API for tunnel CRUD, adapter config, message log, and start/stop. The Vue workbench `/#/tunnels` tab proxies `/api` here.

Gradle module: `packages/wlya-desktop`. Depends on `wlya-core` and generated adapter bindings.

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

## phone-manager and phone-mcp

**`packages/phone-manager`** (`:18082`) sits between the workbench Control tab and a running tunnel. It does not talk to ADB directly: it enqueues gesture/screenshot commands, `wlya-desktop` forwards them through the tunnel to the Gateway APK, and results return on the same channel.

**`packages/phone-mcp`** (`:18083/mcp`) exposes a compact MCP tool surface over phone-manager for agents (look, tap, nav, …). Spec: [`control-protocol.md`](control-protocol.md).

Env: `WLYA_TUNNEL_URL` (desktop base), `PORT`, `HOST`.

---

## Gateway APK vs Bekon Phone

Both are Android apps built from this monorepo; they target different roles.

| | **Gateway** (`apps/gateway`) | **Bekon Phone** (`apps/phone`) |
|--|------------------------------|--------------------------------|
| Package | `pro.potoki.bekon` | `pro.potoki.bekon.phone` (module `bekon-phone`) |
| Primary role | Tunnel endpoint + Control (screen, gestures, a11y) | Line client — voice / GSM bridge |
| Shared code | `wlya-core`, adapters, `bekon-call` | `bekon-call` |
| Deploy script | `tools/adb/gateway` | `tools/adb/phone` |
| Workbench tab | Tunnels (config) + Control (queue) | Voice (companion) |

One physical phone can run Gateway with tunnel + Control; a second device or the same device in another profile may run Bekon Phone for Line. Product modes are documented in [`BRAND.md`](BRAND.md) — Play flavor is tunnel + radio only; full Control is the GitHub APK.

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

Joining voice does not automatically join a tunnel channel, and vice versa. Configure both in the workbench or app settings.

Line audio modes (walkie, acoustic GSM, root ALSA bridge): [`line.md`](line.md). Optional Magisk module: [`tools/magisk/wlya-voice/`](../tools/magisk/wlya-voice/README.md).

---

## Local dev stack

`npm run dev:start` (`scripts/dev-start.mjs`) brings up:

1. Vite — `apps/workbench`
2. `wlya-desktop` — `:18080`
3. `phone-manager` — `:18082`

Relay is started separately (`packages/wlya-server/docker compose` or `scripts/relay`). MCP is optional (`packages/phone-mcp/server.py`).

---

## Related docs

- [`PROTOCOL.md`](PROTOCOL.md) — headers, query params, HMAC
- [`control-protocol.md`](control-protocol.md) — Control queue kinds
- [`line.md`](line.md) — GSM / voice roadmap
- [`packages/wlya-server/README.md`](../packages/wlya-server/README.md) — relay deploy
- [`packages/wlya-adapters/README.md`](../packages/wlya-adapters/README.md) — new adapter
