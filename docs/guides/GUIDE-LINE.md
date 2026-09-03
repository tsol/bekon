# Guide: Line (emigrant gateway)

Leave a **rooted Android with a local SIM** at home. Call and receive GSM through that handset from abroad — commissariat, banks, whoever still trusts a domestic number.

This guide is the **happy path today**. Roadmap items (remote dial/answer, multi-instance voice): [`USE-CASES.md`](../USE-CASES.md#1-emigrant-gateway--line).

**Commands index:** [`COMMANDS.md`](../COMMANDS.md) · **Voice modes:** [`LINE.md`](../LINE.md) · **Wire format:** [`PROTOCOL.md`](../PROTOCOL.md)

---

## What you need

| Piece | Role |
|-------|------|
| **Home phone** | Rooted Android + local SIM. Runs **Gateway** (`apps/android-gateway`) — tunnel + Line audio on the device. |
| **Your phone / laptop** | **Bekon Phone** (`apps/android-phone`) and/or **desktop-ui → Voice** tab. |
| **Relay (wlya-server)** | Public HTTPS/WSS endpoint both sides can reach. Tunnel inbox + `/v1/call` voice rooms. |

Tunnel channel and voice **room** are separate namespaces on the same relay host — see [`ARCHITECTURE.md`](../ARCHITECTURE.md#voice-room-vs-tunnel-channel).

---

## 0. Clone and install

```bash
git clone https://github.com/tsol/bekon.git
cd bekon

npm run install:all
```

---

## 1. Relay — pick one

Line only works if **home phone and your client** can both reach the relay. `relay:compose` on localhost is fine for a **desk test** on one network; for real emigrant use you need a **public** relay.

### A. Your own server (recommended for production)

Deploy `packages/wlya-server` to a VPS you control (any country, your domain).

```bash
cp .env.deploy.example .env.deploy
# edit: DEPLOY_HOST, DEPLOY_DIR, DEPLOY_HEALTH

npm run relay:deploy
```

Check:

```bash
curl -sS https://YOUR-RELAY.example/health
# → {"ok":true}
```

Use `https://YOUR-RELAY.example` in Gateway, tunnel adapter, and voice clients. Details: [`packages/wlya-server/README.md`](../../packages/wlya-server/README.md).

### B. Local Docker (dev / same LAN)

```bash
npm run relay:compose
curl -sS http://127.0.0.1:18081/health
```

Relay URL for adapters: `http://127.0.0.1:18081` (or your laptop’s LAN IP if the phone is on the same Wi‑Fi). Not reachable from another country unless you port-forward or tunnel — use **A** for that.

### C. Shared test relay (`wlya.potoki.pro`)

Author-maintained instance for **quick tests only**:

| | |
|--|--|
| **URL** | `https://wlya.potoki.pro` |
| **Voice** | `wss://wlya.potoki.pro/v1/call` |
| **APK shortcut** | `https://wlya.potoki.pro/u` |

**Do not rely on this for production Line.**

- **Blocked in Russia** (Roskomnadzor). From RU you need your own relay or a VPN — treat this host as unreachable on the domestic internet.
- **Shared / best-effort** — no SLA. Fine for trying the stack, not for “my only way to call the bank.”
- **Optional support:** ~**$1/month** donation if you keep using it beyond a one-off test (helps cover the VPS). No paywall in the app; this is courtesy on a hobby relay.

For anything serious: **option A**.

---

## 2. Laptop stack

```bash
npm run stack:start
```

Opens **desktop-ui** (`:5174`), **wlya-tunnel** (`:18080`), **phone-control-api** (`:18082`). Status:

```bash
npm run stack:status
```

Or background:

```bash
npm run stack:start -- --bg
```

---

## 3. Home phone — Gateway APK

USB once (Linux udev):

```bash
npm run gateway:fix-adb    # once
npm run gateway:deploy
```

Or download the debug APK from your relay’s `/u` after `npm run relay:publish-apk` (see [`COMMANDS.md`](../COMMANDS.md)).

On the phone (**Setup**):

1. **Relay / WLYA Server** adapter URL → your relay from step 1 (`https://…` or `http://127.0.0.1:18081` for local).
2. **Channel** + **Secret** — pick a strong pair; same values go on your remote client and in desktop-ui tunnel.
3. Start tunnel; enable **advertise adapters** if you push config from desktop-ui.

Line audio modes (walkie / acoustic GSM / root bridge): [`LINE.md`](../LINE.md). Optional Magisk: [`apps/android-gateway/magisk/wlya-voice/`](../../apps/android-gateway/magisk/wlya-voice/README.md).

---

## 4. Your side — voice client

Pick **one** (or both):

### desktop-ui Voice tab

1. Open `http://127.0.0.1:5174/#/voice` (port from `stack:status`).
2. **Relay URL** → same host as step 1 (`wss://YOUR-RELAY.example/v1/call` or `ws://127.0.0.1:18081/v1/call` locally).
3. **Room** + **Secret** — match Gateway Line / Bekon Phone settings (HMAC join; see [`PROTOCOL.md`](../PROTOCOL.md)).

### Bekon Phone APK (handset client)

```bash
npm run phone-app:deploy
```

Configure the same relay URL, room, and secret in the app.

---

## 5. Match tunnel + voice (checklist)

Use the **same relay host** everywhere:

| Setting | Gateway (home) | desktop-ui Tunnels | Voice (desktop or Phone) |
|---------|----------------|--------------------|---------------------------|
| Relay base | WLYA Server adapter | wlyaserver adapter URL | `wss://…/v1/call` |
| Channel / room id | Channel (tunnel) | Channel | Room name (voice) |
| Secret | Secret | Secret | Secret (HMAC) |

Tunnel keeps the device reachable for config and Control; voice is the audio path for Line. They are not auto-linked — configure both.

---

## 6. Interactive wizard (optional)

```bash
npm run demo
# pick goal 4 for voice/walkie, or 1 for tunnel-first setup
```

Writes `.demo/session.json` with generated channel/secret. Set `RELAY_URL=https://wlya.potoki.pro` only if you accept the limits in **§1C**.

---

## Stop / reset

```bash
npm run stack:stop
npm run relay:stop          # host Node relay, if used
docker compose -f packages/wlya-server/docker-compose.yml down   # local compose stack
```

---

## Troubleshooting

| Symptom | Check |
|---------|--------|
| Voice never connects | Relay URL must be `wss://` on HTTPS relays; room + secret match; `GET /health` on relay. |
| Works on Wi‑Fi, not from abroad | You used local `127.0.0.1` relay — deploy **§1A**. |
| From Russia, `wlya.potoki.pro` dead | Expected (RKN). Use your own VPS. |
| Tunnel up, no audio | [`LINE.md`](../LINE.md) — mode A vs B vs C; GSM call state on home phone. |

---

## Related guides

| Guide | Use case |
|-------|----------|
| [`GUIDE-CONTROL.md`](GUIDE-CONTROL.md) | Agent + MCP (Control) |
| [`GUIDE-SPEAKER.md`](GUIDE-SPEAKER.md) | Room speaker / walkie |
| [`GUIDE-WLYA.md`](GUIDE-WLYA.md) | Backup adapters (email, …) |
