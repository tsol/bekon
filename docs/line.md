# Bekon Line — voice / GSM bridge

Product overview for **Bekon Line**: walkie-talkie and GSM call audio over WebSocket, separate from the WLYA message tunnel.

Configure clients with your **self-hosted** relay WebSocket URL (example: `wss://your-relay.example/v1/call`) and a **room** name. The message tunnel (`wlyaserver` HTTP poll) is not used for voice.

Server: `wlya-server` `/v1/call` — HMAC join, in-memory rooms (single instance is fine for personal use). See [`packages/wlya-server/README.md`](../packages/wlya-server/README.md).

One Android service + one desktop Voice tab. Root is optional; the app probes capabilities and shows only working modes.

## Audio modes (phone)

| Mode | When | Source → WS | WS → sink | Root |
|------|------|-------------|-----------|------|
| **A. Walkie-talkie** | no GSM call | microphone | speaker | no |
| **B. Acoustic GSM** | GSM call, no root | mic hears speaker (downlink) | speaker (hopes GSM uplink picks it up) | no |
| **C. ALSA / HAL bridge** | GSM call + root | electrical downlink | electrical uplink | yes |

Switching: idle → A; off-hook → best of B/C; hang up → A again.

## Roadmap (compressed)

1. **Channel** — WS join + binary PCM frames (not JSON). Desktop tab: URL, room, secret. Phone foreground service. Criterion: two clients exchange frames in one room.
2. **Walkie (A)** — `AudioRecord` / `AudioTrack` on phone; `getUserMedia` on desktop. PCM 16 kHz mono or Opus. PTT acceptable on first prototype.
3. **Acoustic GSM (B)** — speakerphone + same mic path as A. Quality varies by OEM; some devices cannot capture call audio without root. Honest UI when B fails on a device.
4. **Root bridge (C)** — Magisk module in [`root-phone/`](../root-phone/README.md): `CAPTURE_AUDIO_OUTPUT`, call-audio routing. Criterion: GSM party hears desktop without speakerphone.

Dial/answer/hold are out of scope until the pipe works. Redis-backed rooms and extra encryption beyond TLS are later.

## Desktop vs phone

The desktop Voice tab always uses local mic/speaker. The phone chooses A/B/C. Desktop does not need to know the phone mode.

## Related docs

- Magisk / root kit: [`root-phone/README.md`](../root-phone/README.md)
- Screen capture pitfalls (gateway): [`screencapture-pitfalls.md`](screencapture-pitfalls.md)
- Brand / Play vs full APK: [`BRAND.md`](BRAND.md)
