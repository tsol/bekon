# WLYA-Server

Публичный message relay: **Node.js + Redis**, Docker-контейнер. Без Java/Kotlin/`wlya-core`.

Клиентский адаптер (Kotlin) живёт отдельно: [`../wlya-adapters/wlyaserver/`](../wlya-adapters/wlyaserver/).

Домены (независимые инстансы): `wlya.potoki.pro` / `wlya2.potoki.pro`. URL задаётся в клиентском адаптере.

```
клиент (wlya-adapters/wlyaserver)
        HTTPS / WS + HMAC
wlya-server (этот каталог)
        Redis TTL
```

Сервер хранит только opaque blob. Payload не расшифровывает.

Рабочий каталог везде ниже: `packages/wlya-server/` (этот README).

Требования: **Node 20+**, **npm**, **Docker + Compose** (для контейнера), **Redis 7** (если без Docker).

Переменные окружения:

| Переменная | Default | Смысл |
|------------|---------|--------|
| `PORT` | `18081` | HTTP listen |
| `REDIS_URL` | `redis://127.0.0.1:6379` | Redis |
| `MSG_TTL_SEC` | `600` | TTL blob |
| `CURSOR_TTL_SEC` | `900` | TTL курсора |
| `HMAC_SKEW_SEC` | `120` | допуск timestamp |
| `READ_RATE` | `10` | GET/sec на client |
| `WRITE_RATE` | `5` | POST/sec на client |

Проверки:

- `GET /health` → `{ "ok": true }` (Redis жив; иначе 503)
- `GET /metrics` → Prometheus text

---

## Сборка

### Локально (без Docker)

```bash
cd /path/to/bekon/packages/wlya-server
npm ci
npm test
npm run build          # tsc → dist/server.js
```

Артефакт: `dist/` + `node_modules` (prod). Старт: `REDIS_URL=... npm start`.

### Docker-образ

```bash
cd /path/to/bekon/packages/wlya-server
docker compose build
# или точечно:
docker build -t wlya-server:local .
```

Multi-stage: build (tsc) → runtime `node:20-alpine` + `npm ci --omit=dev`. Compose поднимает ещё `redis:7-alpine`.

---

## Запуск

### Dev с хоста (start / stop)

Из корня `bekon`:

```bash
./packages/wlya-server/scripts/relay start              # http://127.0.0.1:18081
PORT=18082 ./packages/wlya-server/scripts/relay start    # другой порт
./packages/wlya-server/scripts/relay status
./packages/wlya-server/scripts/relay stop               # Redis не трогает
```

Скрипт: если Redis на `:6379` уже есть — использует его, иначе поднимает docker-контейнер `wlya-redis`. Node: `npm run dev:nowatch` (`tsx` без inotify — на этой машине `tsx watch` часто падает с ENOSPC). Лог: `packages/wlya-server/.dev/server.log`. Для hot-reload вручную: `npm run dev`.

### Dev вручную (hot reload, Redis на хосте)

```bash
# отдельный Redis, если ещё нет
docker run -d --name wlya-redis -p 6379:6379 redis:7-alpine

cd /path/to/bekon/packages/wlya-server
npm ci
REDIS_URL=redis://127.0.0.1:6379 npm run dev
```

Слушает `http://127.0.0.1:18081`.

### Prod-like на машине (Docker Compose)

```bash
cd /path/to/bekon/packages/wlya-server
docker compose up -d --build
docker compose ps
curl -sS http://127.0.0.1:18081/health
docker compose logs -f app
```

Стоп: `docker compose down` (volume `redis_data` остаётся). Полный сброс данных: `docker compose down -v`.

Порт с хоста: **18081 → app:18081**. TLS в compose нет — снаружи обычно nginx/caddy.

---

## Деплой по SSH (теория)

Два независимых инстанса, без общего Redis: например `wlya.potoki.pro` и `wlya2.potoki.pro`. На каждом — свой compose. Клиент выбирает URL в адаптере.

Идея: на сервере каталог с этим проектом (git clone или rsync), Docker Compose, reverse proxy на 443.

### 1. Один раз на сервере

Нужны Docker Engine + Compose plugin, git (или rsync с ноутбука).

```bash
ssh user@wlya.potoki.pro
# docker + compose уже стоят
sudo mkdir -p /opt/wlya-server
sudo chown "$USER":"$USER" /opt/wlya-server
```

TLS: DNS A-запись на IP сервера. Сертификат — certbot/caddy. Приложение слушает только localhost:18081.

Пример nginx (после того как compose слушает 18081):

```nginx
server {
    listen 443 ssl http2;
    server_name wlya.potoki.pro;
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

Опционально не публиковать 18081 наружу: в `docker-compose.yml` заменить `"18081:18081"` на `"127.0.0.1:18081:18081"`.

### 2. Выкат кода

**Вариант A — git на сервере**

```bash
# с ноутбука
ssh user@wlya.potoki.pro 'cd /opt/wlya-server && git pull --ff-only'
ssh user@wlya.potoki.pro 'cd /opt/wlya-server/wlya-server && docker compose up -d --build'
```

Если репозиторий — весь `bekon`, compose запускать из подкаталога `packages/wlya-server/`.

**Вариант B — rsync только этого каталога**

```bash
# с ноутбука, из wlya-tunnel/
rsync -az --delete \
  --exclude node_modules --exclude dist \
  ./wlya-server/ user@wlya.potoki.pro:/opt/wlya-server/

ssh user@wlya.potoki.pro 'cd /opt/wlya-server && docker compose up -d --build'
```

`--delete` снимает лишние файлы на сервере; не используй, если там лежат секреты вне git.

Второй хост — те же команды с `user@wlya2.potoki.pro`.

### 3. Проверка после выката

```bash
ssh user@wlya.potoki.pro 'curl -sS http://127.0.0.1:18081/health'
curl -sS https://wlya.potoki.pro/health
ssh user@wlya.potoki.pro 'cd /opt/wlya-server && docker compose ps && docker compose logs --tail=80 app'
```

Откат: предыдущий git commit + снова `docker compose up -d --build`, либо `docker compose down` и поднять старый тег образа, если тегируешь сборки (`wlya-server:2026-08-12`).

### 4. Что не делать

- Не шарить один Redis между `wlya` и `wlya2`.
- Не открывать Redis в интернет (в compose порт Redis на хост не проброшен — так и оставить).
- Не класть seed в URL/логи; HMAC заголовки не логировать целиком.

---

## API


Auth на каждый запрос (кроме `/health`, `/metrics`):

`X-WLYA-Seed` — **channel id** (очередь Redis + HMAC). Имя заголовка историческое: это не AES-секрет туннеля. Payload шифруется на клиентах отдельным `secret`; сервер его не видит.

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

`body` — сырой POST body; для GET пустая строка. Отклонение при неверном HMAC или `|now - ts| > 120s`.

### GET `/v1/messages`

Query: `cursor=<offset>` (опционально).

```json
{
  "messages": [
    { "id": "...", "offset": 12345, "data": "<base64>", "ts": 1234567890 }
  ],
  "next_cursor": 12360
}
```

TTL сообщений: 10 мин. Курсор в Redis: `seed+client → offset`, TTL 15 мин. Нет курсора — последние 10 сообщений.

### POST `/v1/messages`

```json
{ "messages": [ { "id": "<uuid>", "data": "<base64>", "ts": 1234567890 } ] }
```

Ответ: `{ "stored": 3 }`

Лимит тела: Fastify `bodyLimit` 4 MiB. nginx `client_max_body_size 8m`.

Rate limit: 10 read/sec, 5 write/sec на `(seed, client)`. Иначе `429`.

### GET `/v1/stream` (SSE)

Тот же auth. Event `message` на каждый новый blob канала. Клиент сам делает POST.

### WS `/v1/call`

Subprotocol `wlya-call/1.0`, те же заголовки (или query `seed`, `client`, `ts`, `sig` если клиент не умеет WS headers).

Первый JSON: `{ "type": "join", "room": "<id>" }`. `join` только серверный. Остальной JSON (`type !== "join"`) и бинарные кадры (первый байт не `0x7b`) релеятся в комнату as-is, без эха отправителю. PCM: `[0xa1][int16 LE samples]`.

Room живёт, пока есть участники.

## Redis

Ключи используют `sha256(channel)` hex (`X-WLYA-Seed`), не сырой channel и не AES-secret:

```
wlya:seq:{h}                 — monotonic offset
wlya:msg:{h}:{offset}        — JSON blob, TTL 10min
wlya:idx:{h}                 — sorted set offset→offset, TTL 10min
wlya:cursor:{h}:{client}     — last read offset, TTL 15min
wlya:pub:{h}                 — pub/sub для SSE
wlya:ratelimit:r:{h}:{c}     — read window
wlya:ratelimit:w:{h}:{c}     — write window
```

## Криптография

| Где | Что |
|-----|-----|
| Этот сервер | только HMAC auth, blob as-is |
| Клиент (`Crypto.kt`) | AES-256-GCM от **secret** (если пуст — channel), PBKDF2 salt `tunnel-v1`, 100_000 iter |

## Решения

1. Seed в заголовке, не в URL
2. HMAC на каждый запрос
3. Redis TTL — auto-cleanup
4. WS PCM — бинарный relay без обработки
5. Инстансы независимы; клиент выбирает URL
