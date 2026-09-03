# WLYA Server

Public message relay: **Node.js + Redis**, typically Docker. No Java/Kotlin/`wlya-core` in this package.

Kotlin client adapter: [`../wlya-adapters/wlyaserver/`](../wlya-adapters/wlyaserver/). Set the relay URL in that adapter (or in voice clients). Example hostnames below are placeholders — run **your** instance.

```
client (wlya-adapters/wlyaserver)
        HTTPS / WS + HMAC
wlya-server (this directory)
        Redis TTL
```

The server stores opaque blobs only. It does not decrypt tunnel payloads.

Working directory for commands: `packages/wlya-server/`.

Requirements: **Node 20+**, **npm**, **Docker Compose** (for the container stack), **Redis 7** (if you run without Docker).

| Variable | Default | Meaning |
|----------|---------|---------|
| `PORT` | `18081` | HTTP listen |
| `REDIS_URL` | `redis://127.0.0.1:6379` | Redis |
| `MSG_TTL_SEC` | `600` | Blob TTL |
| `CURSOR_TTL_SEC` | `900` | Cursor TTL |
| `HMAC_SKEW_SEC` | `120` | Timestamp skew |
| `READ_RATE` | `10` | GET/sec per client |
| `WRITE_RATE` | `5` | POST/sec per client |

Checks:

- `GET /health` → `{ "ok": true }` (503 if Redis is down)
- `GET /metrics` → Prometheus text

Wire auth: [`docs/PROTOCOL.md`](../../docs/PROTOCOL.md).

---

## Build

### Local (no Docker)

```bash
cd /path/to/bekon/packages/wlya-server
npm ci
npm test
npm run build          # tsc → dist/server.js
```

Artifact: `dist/` + production `node_modules`. Start: `REDIS_URL=... npm start`.

### Docker image

```bash
cd /path/to/bekon/packages/wlya-server
docker compose build
# or:
docker build -t wlya-server:local .
```

Multi-stage: `tsc` → `node:20-alpine` + `npm ci --omit=dev`. Compose also starts `redis:7-alpine`.

---

## Run

### Dev from the repo root

```bash
./packages/wlya-server/scripts/relay start              # http://127.0.0.1:18081
PORT=18082 ./packages/wlya-server/scripts/relay start
./packages/wlya-server/scripts/relay status
./packages/wlya-server/scripts/relay stop               # leaves Redis running
```

If Redis already listens on `:6379`, the script uses it; otherwise it starts a `wlya-redis` container. Node: `npm run dev:nowatch` (`tsx watch` can hit ENOSPC). Log: `packages/wlya-server/.dev/server.log`. Hot reload: `npm run dev`.

### Dev with Redis on the host

```bash
docker run -d --name wlya-redis -p 6379:6379 redis:7-alpine

cd /path/to/bekon/packages/wlya-server
npm ci
REDIS_URL=redis://127.0.0.1:6379 npm run dev
```

Listens on `http://127.0.0.1:18081`.

### Prod-like (Docker Compose)

```bash
cd /path/to/bekon/packages/wlya-server
docker compose up -d --build
docker compose ps
curl -sS http://127.0.0.1:18081/health
docker compose logs -f app
```

Stop: `docker compose down` (volume `redis_data` stays). Wipe data: `docker compose down -v`.

Host port **18081 → app:18081**. Compose has no TLS — put nginx/Caddy in front.

---

## Deploy over SSH

Independent instances, **no shared Redis**. Each host has its own compose. The client picks the URL.

On the server: this package (git clone or rsync), Docker Compose, reverse proxy on 443.

### 1. Once on the server

Docker Engine + Compose plugin, git (or rsync).

```bash
ssh user@relay.example
sudo mkdir -p /opt/wlya-server
sudo chown "$USER":"$USER" /opt/wlya-server
```

TLS: DNS A record, certbot/Caddy. App listens on localhost:18081.

Example nginx (after compose is up):

```nginx
server {
    listen 443 ssl http2;
    server_name relay.example;
    # ssl_certificate ...;
    # ssl_certificate_key ...;

    location / {
        proxy_pass http://127.0.0.1:18081;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";  # WS /v1/call
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 3600s;
        proxy_buffering off;                    # SSE /v1/stream
    }
}
```

Optionally bind compose to loopback only: `"127.0.0.1:18081:18081"`.

### 2. Ship code

**A — git on the server** (whole `bekon` clone): compose from `packages/wlya-server/`.

```bash
ssh user@relay.example 'cd /opt/wlya-server && git pull --ff-only'
ssh user@relay.example 'cd /opt/wlya-server/packages/wlya-server && docker compose up -d --build'
```

**B — rsync this package only**

```bash
rsync -az --delete \
  --exclude node_modules --exclude dist \
  ./packages/wlya-server/ user@relay.example:/opt/wlya-server/

ssh user@relay.example 'cd /opt/wlya-server && docker compose up -d --build'
```

Do not use `--delete` if the server holds secrets outside git.

### 3. After deploy

```bash
ssh user@relay.example 'curl -sS http://127.0.0.1:18081/health'
curl -sS https://relay.example/health
ssh user@relay.example 'cd /opt/wlya-server && docker compose ps && docker compose logs --tail=80 app'
```

Rollback: previous git commit + `docker compose up -d --build`, or retag images.

### 4. Do not

- Share one Redis across two public hostnames.
- Expose Redis to the internet (compose does not publish Redis — keep it that way).
- Put the channel id in URLs or logs; do not log full HMAC headers.

---

## API

Auth on every request except `/health` and `/metrics`:

`X-WLYA-Seed` is the **channel id** (Redis queue + HMAC). The header name is historical — it is not the AES payload key. Clients encrypt with `secret`; this server never sees plaintext.

```
X-WLYA-Seed: <channel>
X-WLYA-Client: <uuid>
X-WLYA-Timestamp: <unix seconds>
X-WLYA-Sig: <hex HMAC-SHA256>
```

```
key = SHA-256(channel)                    # raw 32 bytes
sig = HMAC-SHA256(key, channel + timestamp + body)
```

`body` is the raw POST body; GET uses an empty string. Reject on bad HMAC or `|now - ts| > 120s`.

### GET `/v1/messages`

Query: `cursor=<offset>` (optional).

```json
{
  "messages": [
    { "id": "...", "offset": 12345, "data": "<base64>", "ts": 1234567890 }
  ],
  "next_cursor": 12360
}
```

Message TTL: 10 min. Cursor in Redis: `seed+client → offset`, TTL 15 min. No cursor → last 10 messages.

### POST `/v1/messages`

```json
{ "messages": [ { "id": "<uuid>", "data": "<base64>", "ts": 1234567890 } ] }
```

Response: `{ "stored": 3 }`

Body limit: Fastify `bodyLimit` 4 MiB. nginx `client_max_body_size 8m`.

Rate limit: 10 read/sec, 5 write/sec per `(seed, client)` or `429`.

### GET `/v1/stream` (SSE)

Same auth. Event `message` for each new blob. The client still POSTs.

### WS `/v1/call`

Subprotocol `wlya-call/1.0`, same headers (or query `seed`, `client`, `ts`, `sig` if the client cannot set WS headers).

First JSON: `{ "type": "join", "room": "<id>" }`. Only the server handles `join`. Other JSON (`type !== "join"`) and binary frames (first byte not `0x7b`) are relayed to the room as-is, without echoing to the sender. PCM: `[0xa1][int16 LE samples]`.

A room lives while it has members.

## Redis

Keys use `sha256(channel)` hex (`X-WLYA-Seed`), not the raw channel and not the AES secret:

```
wlya:seq:{h}                 — monotonic offset
wlya:msg:{h}:{offset}        — JSON blob, TTL 10min
wlya:idx:{h}                 — sorted set offset→offset, TTL 10min
wlya:cursor:{h}:{client}     — last read offset, TTL 15min
wlya:pub:{h}                 — pub/sub for SSE
wlya:ratelimit:r:{h}:{c}     — read window
wlya:ratelimit:w:{h}:{c}     — write window
```

## Crypto

| Where | What |
|-------|------|
| This server | HMAC auth only; blobs as-is |
| Client (`Crypto.kt`) | AES-256-GCM from **secret** (if blank — channel), PBKDF2 salt `tunnel-v1`, 100_000 iter |

## Design notes

1. Channel id in a header, not in the URL
2. HMAC on every request
3. Redis TTL for cleanup
4. WS PCM is a dumb binary relay
5. Instances are independent; the client chooses the URL
