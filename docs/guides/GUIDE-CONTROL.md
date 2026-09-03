# Guide: Control (agent + phone)

Give an agent **a real Android body** — tap, swipe, open apps, read the screen. **Bekon Control** = Gateway APK + `phone-control-api` + optional `phone-control-mcp`. No ADB on the happy path; work goes through the WLYA tunnel queue.

**Spec:** [`CONTROL-PROTOCOL.md`](../CONTROL-PROTOCOL.md) · **MCP tools:** [`apps/phone-control-mcp/README.md`](../../apps/phone-control-mcp/README.md) · **Commands:** [`COMMANDS.md`](../COMMANDS.md)

---

## What you need

| Piece | Role |
|-------|------|
| **Android phone** | Gateway APK (`pro.potoki.bekon`) — screen capture, a11y, gestures, IME. |
| **Laptop** | `stack:start` — desktop-ui, wlya-tunnel, phone-control-api. |
| **Relay** | Same as any tunnel use case — phone must reach your relay. See [`GUIDE-LINE.md`](GUIDE-LINE.md#1-relay--pick-one) (§1A–C). |
| **Agent (optional)** | MCP client pointed at `phone-control-mcp` on `:18083`. |

---

## 0. Clone and install

```bash
git clone https://github.com/tsol/bekon.git
cd bekon

npm run install:all
```

---

## 1. Relay

Control needs a **running tunnel** to the phone. Pick a relay host (own VPS, local Docker, or test `wlya.potoki.pro` — see [`GUIDE-LINE.md`](GUIDE-LINE.md#1-relay--pick-one)).

**Local quick test:**

```bash
npm run relay:compose
curl -sS http://127.0.0.1:18081/health
```

**Production:** `npm run relay:deploy` after `.env.deploy` (see [`COMMANDS.md`](../COMMANDS.md#relay-wlya-server)).

---

## 2. Laptop stack

```bash
npm run stack:start
```

Check:

```bash
npm run stack:status
```

Open **Control** tab later: `http://127.0.0.1:5174/#/phone` (port from status).

Background:

```bash
npm run stack:start -- --bg
```

---

## 3. Phone — Gateway APK

```bash
npm run gateway:fix-adb    # Linux, once
npm run gateway:deploy
```

On the phone (**Setup**):

1. Grant accessibility, overlay, notifications, screen capture when prompted.
2. **WLYA Server** adapter → your relay URL.
3. **Channel** + **Secret** — remember these for desktop-ui.

---

## 4. desktop-ui — tunnel + Control tab

1. Open `http://127.0.0.1:5174/#/tunnels`.
2. **Create tunnel** with the **same Channel + Secret** as the phone.
3. Add **wlyaserver** adapter (relay URL), **Start** tunnel.
4. On the phone: enable **Accept advertised adapters** → **Start All** (or mirror config manually).
5. When tunnel shows **running**, open **Control** tab (`/#/phone`).
6. Pick the tunnel in the picker — screenshots and gesture queue should respond.

Manual queue / API: [`CONTROL-PROTOCOL.md`](../CONTROL-PROTOCOL.md).

---

## 5. Agent — phone-control-mcp (optional)

Install Python deps once:

```bash
cd apps/phone-control-mcp
pip install -r requirements.txt
```

From repo root:

```bash
npm run phone-control-mcp
# listens on http://0.0.0.0:18083/mcp
```

Requires `phone-control-api` on `:18082` and at least one **running** tunnel (`npm run stack:start`).

**MCP client example:**

```yaml
mcp_servers:
  phone:
    enabled: true
    url: http://127.0.0.1:18083/mcp
    timeout: 180
```

**Smoke test:**

```bash
curl -s http://127.0.0.1:18083/mcp | head
```

Tools: `look`, `open`, `find`, `act`, `reset`, `see`. Every mutating call ends with a fresh snapshot — do not cache stale `look` output.

Env vars: `PHONE_API`, `PHONE_TUNNEL`, `PHONE_SHOTS` — see [`apps/phone-control-mcp/README.md`](../../apps/phone-control-mcp/README.md).

---

## 6. Interactive wizard (optional)

```bash
npm run demo
# choose 2) Remote phone control  or  3) Agent MCP
```

---

## Stop / reset

```bash
npm run stack:stop
```

---

## Troubleshooting

| Symptom | Check |
|---------|--------|
| Control tab empty / no tunnel | Tunnel must be **running** on both desktop-ui and phone; channel/secret match. |
| `phone-control-api` EADDRINUSE | `npm run stack:stop`; kill stale `:18082` (see [`COMMANDS.md`](../COMMANDS.md)). |
| MCP “no tunnels” | `curl -s http://127.0.0.1:18082/tunnels` — need `running: true`. |
| Black / stale screenshot | Gateway permissions; wake screen; [`CONTROL-PROTOCOL.md`](../CONTROL-PROTOCOL.md) snapshot rules. |
| Queue 409 | Another execute in flight — wait or `POST .../queue/abort`. |

---

## Related guides

| Guide | Use case |
|-------|----------|
| [`GUIDE-LINE.md`](GUIDE-LINE.md) | GSM / voice (separate from Control queue) |
| [`GUIDE-SPEAKER.md`](GUIDE-SPEAKER.md) | Walkie / room audio without MCP |
| [`GUIDE-WLYA.md`](GUIDE-WLYA.md) | Backup transports when relay is blocked |
