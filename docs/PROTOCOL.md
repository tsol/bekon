# WLYA protocol (brief)

Wire authentication and naming for the public relay (`packages/wlya-server`) and Kotlin clients (`wlya-core`, `bekon-call`). Full relay API: [`packages/wlya-server/README.md`](../packages/wlya-server/README.md).

---

## Two secrets (do not confuse)

| | Protocol / JSON | UI label | Role |
|--|-----------------|----------|------|
| **Channel id** | `seed` (field name unchanged for compatibility) | Secret | HMAC key material; Redis queue namespace; relay never sees payload plaintext |
| **Payload key** | `secret` (optional) | Secret (encryption) | AES-GCM for tunnel message bodies; blank → use channel id |

In `TunnelConfig`, the channel is `channel` with `@JsonNames("seed")`. UI may show one “Secret” field for humans; on the wire the HMAC parameter remains `seed`.

---

## HMAC authentication

Used on `GET/POST /v1/messages`, `GET /v1/stream`, and WebSocket `GET /v1/call`.

**Headers** (preferred on HTTP):

| Header | Meaning |
|--------|---------|
| `X-WLYA-Seed` | Channel id |
| `X-WLYA-Client` | Client instance id |
| `X-WLYA-Timestamp` | Unix seconds |
| `X-WLYA-Sig` | Hex HMAC-SHA256 |

**Query string** (WebSocket and links): `seed`, `client`, `ts`, `sig` — same values as headers.

Signing (TypeScript reference in `packages/wlya-server/src/auth.ts`):

```
key  = SHA256(seed)
sig  = HMAC-SHA256(key, seed + timestamp + body)
```

- HTTP `GET`: `body` is empty string.
- HTTP `POST`: `body` is the raw JSON body bytes.
- `/v1/call` WebSocket upgrade: `body` is empty; auth via query or headers.

Timestamp skew default ±120 s (`HMAC_SKEW_SEC`).

---

## Relay HTTP inbox

Opaque encrypted blobs only — the server does not parse tunnel plaintext.

| Method | Path | Action |
|--------|------|--------|
| `GET` | `/v1/messages?cursor=` | Poll messages for this channel + client |
| `POST` | `/v1/messages` | Push `{ "messages": [{ "id", "data", "ts" }] }` |
| `GET` | `/v1/stream` | SSE: `event: message` when new data arrives |

`data` is base64 ciphertext produced by `wlya-core` before send. Adapter: `packages/wlya-adapters/wlyaserver/`.

---

## Voice WebSocket (`/v1/call`)

Separate from the message inbox. Clients use `packages/bekon-call`:

- Connect to `wss://your-relay/v1/call?seed=…&client=…&ts=…&sig=…`
- After join, send a **room** name (not the tunnel channel id unless you choose to reuse it)
- Binary frames carry PCM audio — not JSON tunnel messages

Implementation: `WlyaCallClient.kt`, `VoiceHmac.kt`. Desktop Voice tab and Bekon Phone share this library.

---

## Kotlin tunnel config

```kotlin
// packages/wlya-core — Types.kt
data class TunnelConfig(
    val channel: String,  // JSON alias "seed"
    val secret: String = "",
    ...
) {
    fun cryptoSecret(): String = secret.ifBlank { channel }
}
```

Legacy stores with only `"seed"` load correctly as `channel`.

---

## Further reading

- Architecture overview: [`ARCHITECTURE.md`](ARCHITECTURE.md)
- Control commands (inside encrypted tunnel): [`CONTROL-PROTOCOL.md`](CONTROL-PROTOCOL.md)
- Voice / GSM: [`LINE.md`](LINE.md)
- Use cases: [`USE-CASES.md`](USE-CASES.md)
- Relay deploy and env vars: [`packages/wlya-server/README.md`](../packages/wlya-server/README.md)
