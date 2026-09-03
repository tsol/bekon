# Phone MCP

HTTP facade over phone-manager (`:18082`) for agent tooling. One process, one phone, compact looks, screenshots as file paths.

Agents should call this server. Raw queue protocol: [`docs/control-protocol.md`](../../docs/control-protocol.md).

The Gateway APK already wakes the display (`Wake if idle`, default 3000 ms). This server does **not** tap the screen to wake it.

## Run

```bash
cd packages/phone-mcp
pip install -r requirements.txt
python3 server.py
# listens on 0.0.0.0:18083/mcp
```

Tests (no phone): `python3 -m unittest test_look test_nav`  
Live look (needs `:18082` + running tunnel): `python3 -m unittest test_live`  
Telegram open: `TELEGRAM_SMOKE=1 python3 -m unittest test_live`

Env:

| Variable | Default | Meaning |
|----------|---------|---------|
| `PHONE_API` | `http://127.0.0.1:18082` | phone-manager base URL |
| `PHONE_TUNNEL` | first **running** tunnel | skip auto-pick |
| `PHONE_MCP_HOST` | `0.0.0.0` | bind address |
| `PHONE_MCP_PORT` | `18083` | bind port |
| `PHONE_SHOTS` | `/tmp/phone-mcp` | JPEG directory (last 50 kept); use `./shots` for a repo-local dir |
| `PHONE_WORKSPACE_HOST` | auto-detect | host path where screenshot bytes are written |
| `PHONE_WORKSPACE_HERMES` | *(unset)* | optional path prefix shown to agents when MCP runs inside a container that maps host files differently |

Screenshot bytes are stored on the host under `PHONE_SHOTS` (or `/tmp/phone-mcp`). `look` returns a path agents can open; set `PHONE_WORKSPACE_HERMES` only if your runtime needs a different visible prefix (e.g. Docker bind-mount).

## MCP client config (example)

After the server is up, point your MCP client at:

```yaml
mcp_servers:
  phone:
    enabled: true
    url: http://127.0.0.1:18083/mcp
    timeout: 180
```

Tools: `look`, `open`, `find`, `act`, `reset`, `see` (prefixed by your client, e.g. `mcp_phone_look`).

## Tools

- **look** — snapshot + jpeg path. Chrome (status/nav) off by default.
- **open(name)** — home, swipe pages both ways, tap label, wait, look.
- **find(query, tap?, scan=screen\|home_pages\|scroll)**
- **act** — tap / swipe / drag / long_press / type / nav. Tap by `ref` from last look. `type` requires `ref` of the field: tap then keys.
- **reset** — back×3 + home×2.
- **see(question)** — path + labels only. Feed the jpeg to your vision model; no VL call here.

Every mutating call ends with a fresh snapshot. Never read `/state.lastSnapshot` yourself.

## Guards baked in

- no `kind: longPress` (hangs the queue) — hold is `drag` + `release`
- no zero-distance swipe
- no vertical swipe on the home screen (app drawer)
- `type` requires `ref` (or x,y): tap the field, then `inputMode=keys`
- `"Главный экран"` is not treated as home
- page swipes stay near y=500
