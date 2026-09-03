# Bekon Line — voice / GSM

Walkie-talkie and GSM call audio over WebSocket. Separate from the WLYA **message** tunnel.

Point clients at **your** relay: `wss://your-relay.example/v1/call`, plus a **room** name. HMAC join uses the same `seed` query/header family as the inbox — see [`PROTOCOL.md`](PROTOCOL.md). Server: `packages/wlya-server` `/v1/call` (in-memory rooms; one process is enough for personal use).

Root is optional. The app should expose only modes that actually work on the device.

## Audio modes (phone)

| Mode | When | Source → WS | WS → sink | Root |
|------|------|-------------|-----------|------|
| **A. Walkie-talkie** | no GSM call | microphone | speaker | no |
| **B. Acoustic GSM** | GSM call, no root | mic hears speaker (downlink) | speaker (uplink by luck) | no |
| **C. ALSA / HAL bridge** | GSM call + root | electrical downlink | electrical uplink | yes |

Idle → A; off-hook → best of B/C; hang up → A.

The desktop Voice tab is always local mic/speaker. It does not need to know A/B/C.

Shared client library: `packages/bekon-call`. Audio: **16 kHz mono PCM**, **10 ms** frames (`0xA1` prefix). Latency presets and buffer multipliers are tunable remotely via voice `ctrl` (Bekon Phone Settings or desktop Voice debug). Magisk module: [`apps/android-gateway/magisk/wlya-voice/`](../apps/android-gateway/magisk/wlya-voice/README.md).

Dial/answer/hold from the remote UI and multi-instance voice rooms are roadmap — [`USE-CASES.md`](USE-CASES.md).
