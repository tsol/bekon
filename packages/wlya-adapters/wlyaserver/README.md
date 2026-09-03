# WLYA Server client adapter

Kotlin HTTP client for the Node relay in [`../../wlya-server`](../../wlya-server). Not the server.

Config: `serverUrl`, optional `clientId`, optional `windowSize` (max HTTP POST bytes, default `262144`). Cipher chunks are smaller so JSON+base64 wrapping still fits. Larger payloads split into multipart. HMAC channel comes from tunnel `init(channel)` and is sent as `X-WLYA-Seed`. AES secret stays in Tunnel/Crypto.
