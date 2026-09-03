# Guide: Speaker (room voice)

Turn an old Android into a **room speaker** — talk to Hermes (or any peer) over walkie-style audio. No Alice, no cloud speaker. Today this is **Line mode A** (mic/speaker over `/v1/call`); always-on wake word is on the roadmap.

**Voice modes:** [`LINE.md`](../LINE.md) · **Relay setup:** [`GUIDE-LINE.md`](GUIDE-LINE.md#1-relay--pick-one) · **Commands:** [`COMMANDS.md`](../COMMANDS.md)

---

## What you need

| Piece | Role |
|-------|------|
| **Table phone** | Gateway APK — walkie capture/playback (mode A). Root **not** required for a first test. |
| **Your laptop or phone** | desktop-ui **Voice** tab and/or **Bekon Phone** APK. |
| **Relay** | Public `wss://…/v1/call` (or local for same-network test). |

Control (screen/MCP) is optional — same Gateway can run tunnel + voice in parallel.

---

## 0. Clone and install

```bash
git clone https://github.com/tsol/bekon.git
cd bekon

npm run install:all
```

---

## 1. Relay

Same three options as Line — [`GUIDE-LINE.md`](GUIDE-LINE.md#1-relay--pick-one).

**Same-network test:**

```bash
npm run relay:compose
```

Voice WebSocket URL: `ws://127.0.0.1:18081/v1/call` (or `wss://YOUR-RELAY.example/v1/call` in production).

---

## 2. Laptop stack

```bash
npm run stack:start
npm run stack:status
```

You only need the stack if you use **desktop-ui Voice** or configure tunnels from the browser. Bekon Phone ↔ Gateway voice can work with relay + Gateway alone once both are configured.

---

## 3. Table phone — Gateway (walkie)

```bash
npm run gateway:fix-adb
npm run gateway:deploy
```

On Gateway:

1. Tunnel (optional but useful): WLYA Server adapter + channel/secret — same as [`GUIDE-LINE.md`](GUIDE-LINE.md#3-home-phone--gateway-apk).
2. **Line / Voice**: set relay URL, **room** name, **secret** (HMAC join).
3. Use **walkie** mode (mode A) — no active GSM call required.

Mic/speaker routing details: [`LINE.md`](../LINE.md#audio-modes-phone).

---

## 4. Your side — join the room

### desktop-ui Voice tab

```bash
# stack must be running
open http://127.0.0.1:5174/#/voice
```

1. **Relay** → `wss://YOUR-RELAY.example/v1/call` (or `ws://127.0.0.1:18081/v1/call` locally).
2. **Room** + **Secret** — must match the table phone.
3. Push-to-talk / listen — desktop uses **your** laptop mic and speakers.

### Bekon Phone (handset client)

```bash
npm run phone-app:deploy
```

Same relay URL, room, and secret in app settings.

---

## 5. Quick demo wizard

```bash
npm run demo
# choose 4) Voice / walkie
```

Then configure Gateway + Voice tab with credentials from `.demo/session.json`.

---

## Stop / reset

```bash
npm run stack:stop
```

---

## Troubleshooting

| Symptom | Check |
|---------|--------|
| No audio either way | Room + secret match; relay `/health`; `wss://` on HTTPS relays. |
| One direction only | Android mic permission; volume; not stuck in GSM mode B/C — use walkie (A). |
| From Russia, shared relay dead | `wlya.potoki.pro` is RKN-blocked — own VPS ([`GUIDE-LINE.md`](GUIDE-LINE.md#c-shared-test-relay-wlyapotokipro)). |
| Want agent on screen too | Add Control path — [`GUIDE-CONTROL.md`](GUIDE-CONTROL.md). |

---

## Related guides

| Guide | Use case |
|-------|----------|
| [`GUIDE-LINE.md`](GUIDE-LINE.md) | Full Line + GSM bridge |
| [`GUIDE-CONTROL.md`](GUIDE-CONTROL.md) | Agent sees and taps the screen |
| [`GUIDE-WLYA.md`](GUIDE-WLYA.md) | Tunnel over email when internet is hostile |
