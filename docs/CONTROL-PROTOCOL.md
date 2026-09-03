# Phone Control — phone-control-api HTTP API

Canonical spec for **phone-control-api** (`:18082`, Vite proxy `/phone-api`). Session state lives on the server. Do **not** talk to the APK or ADB for gestures; enqueue work, execute, then read state / image / snapshot.

Same phone, two layers for the product:

| Who | Use |
|-----|-----|
| **Agents (MCP)** | [`apps/phone-control-mcp`](../apps/phone-control-mcp) (`:18083/mcp`) — compact tools over this queue |
| **This file** | HTTP kinds, endpoints, snapshot rules |

Base (direct): `http://127.0.0.1:18082`  
Base (from the web UI): `/phone-api`

Pick `TUNNEL` from `GET /tunnels` (`id` of a running tunnel). All paths below are `/tunnels/:id/...`.

## Loop

1. `GET /tunnels` → choose `id`
2. Enqueue one item or an array: `POST /queue`
3. `POST /queue/execute` (waits until the phone replies; `409` = already executing — retry). To stop waiting: `POST /queue/abort`
4. Read results from **that execute body** and from `/state` as needed:
   - labels: execute includes `snapshot` **only** if this batch ended with a successful `kind: snapshot` (trailing `sleep` / `ping` ignored). Otherwise there is **no** snapshot field — do not treat `/state.lastSnapshot` as the screen after a tap
   - image: `GET /screenshot` (jpeg or png; see `screenshotMime` on `/state`)
   - last labels: `GET /snapshot` → `{ "snapshot": { ... } }` is the **last** successful snapshot (may be stale)
   - saved file: `/state.lastPutFile` after a `file` command (`path` is always app-private `filesDir/inbox/…`; optional `publicPath` / `uri` if Downloads copy succeeded)
   - phone logs: `/state.lastLogs` after a `logs` command (`adapter`, `messages`, `core`, `apkUpdate`)
   - sizes / errors / queue: `GET /state` (or SSE `GET /events`)

Typical step: **snapshot → pick an `items[]` ref → tap its `x`,`y` → sleep → snapshot**.

```bash
BASE=http://127.0.0.1:18082
ID=<tunnel-id>

curl -s "$BASE/tunnels"
curl -s -X POST "$BASE/tunnels/$ID/queue" -H 'Content-Type: application/json' \
  -d '[{"kind":"tap","x":184,"y":261},{"kind":"sleep","ms":500},{"kind":"snapshot"}]'
curl -s -X POST "$BASE/tunnels/$ID/queue/execute"
# execute JSON has `snapshot` (this batch). `/state.lastSnapshot` is UI cache; may be older.
# PNG/JPEG bytes:
curl -s -o /tmp/phone.png "$BASE/tunnels/$ID/screenshot"
```

`POST /tunnels/:id/snapshot` is a shortcut: **clears the queue**, enqueues `kind: snapshot`, executes, returns `{ "snapshot": ... }`. Prefer the queue when you need tap/sleep before the shot.

## Endpoints

| Method | Path | Role |
|---|---|---|
| GET | `/tunnels` | list `{ id, label, running }` |
| GET | `/tunnels/:id/state` | queue + sizes + `lastSnapshot` + `lastPutFile` (never waits). `lastSnapshot` is the last successful tree — **not** proof the screen is still that |
| GET | `/tunnels/:id/screenshot` | last image bytes |
| GET | `/tunnels/:id/events` | SSE of `/state` (includes `lastSnapshot` for the desktop UI) |
| POST | `/tunnels/:id/queue` | enqueue one object, an array, or `{ items }` |
| DELETE | `/tunnels/:id/queue/:gestureId` | drop one item |
| DELETE | `/tunnels/:id/queue` | clear |
| POST | `/tunnels/:id/queue/execute` | run all **pending** items as **one** phone batch. **No** `lastSnapshot`. Optional `snapshot` if this batch ended with a successful `kind: snapshot` |
| POST | `/tunnels/:id/queue/abort` | stop waiting for the current execute ACK (`{ ok, aborted }`). Does not recall parts already on the relay. `aborted: false` if nothing was running |
| POST | `/tunnels/:id/snapshot` | clear + `snapshot` + execute → `{ snapshot }` |
| GET | `/tunnels/:id/snapshot` | last `{ snapshot }` (`404` if none) — same cache as `/state.lastSnapshot` |

No auth. Env: `PORT`, `HOST`, `WLYA_TUNNEL_URL`.

## Queue kinds

Execute sends one JSON array to the phone: `[{cmd,id,...},...]`. Reply slots match by `id`.

| `kind` | Fields | Notes |
|---|---|---|
| `tap` | `x`, `y` | device screen pixels |
| `swipe` | `x1`,`y1`,`x2`,`y2` | same space. After `drag` (same execute batch) this **moves the held finger** to `x2,y2` instead of a new gesture |
| `longPress` | `x`, `y` | hold ~1.5s then **lift**. Context menus / “press and hold”. Not a drag |
| `drag` | `x`, `y` | finger down, hold ~1.5s, **keep finger down**. Android 8+ |
| `release` | — | lift the finger started by `drag`. Omit and the phone lifts at the end of the batch anyway |
| `nav` | `nav`: `home` \| `back` \| `recentApps` | |
| `input` | `text`, optional `inputMode`: `text` \| `keys` | |
| `key` | `key`: `backspace` \| `enter` \| `selectAll` \| `clear` \| `copy` \| `cut` \| `paste`; `n` for backspace | |
| `clipboard` | — | text appears as `lastClip` on `/state` |
| `sleep` | `ms` | wait **on the phone** before the next slot. Idle time (this wait, or a gap between two execute batches) ≥ the APK Status setting **Wake if idle** (default 3000, 0 = off) wakes the display before the **next** command of any kind — including screenshot / snapshot |
| `screenshot` | optional `hiRes`, `scale` (0.1–1), `quality` (1–100 JPEG) | default 0.5× JPEG q45; `hiRes: true` → 1.0× JPEG q70 |
| `snapshot` | optional `hiRes` (default **true**), same `scale`/`quality` | image + a11y + OCR. `hiRes: false` uses ordinary screenshot. Execute then has `snapshot` **only if** this was the last real command in the batch. `[snapshot, tap]` does **not** return `snapshot`. `[tap, sleep, snapshot]` does |
| `file` | `name`, `data` (base64), optional `mime` | max **25 MB** decoded. Always written under app **`filesDir/inbox`**. Best-effort unique name in public **Downloads**. APK + Status **Auto-update APK**: ACK first, then root `pm install` (reboot only after Success) or an unrooted **system install prompt**. Follow-up tunnel JSON `type: apkUpdate`. `/state.lastPutFile`. Do not put `data` on later `/state` queue rows. |
| `share` | optional `path`, `uri`, `mime`, `package` (or `pkg`) | `ACTION_SEND` with `EXTRA_STREAM`. Omit `path`/`uri` to reuse the last `file`. No `package` → system chooser. Needs rebuilt APK. |
| `ping` | — | RTT is the whole-batch `latencyMs` if mixed with a shot |
| `logs` | optional `n` (10–200, default 80) | Phone Status **Share logs on request** must be on (default on). Reply arrays: adapter, messages, core, apkUpdate. `/state.lastLogs`. |

Plaintext tunnel `PING`/`PONG` is keepalive, not this `kind`.

## Coordinates

`tap` / `swipe` / `longPress` / `drag` and snapshot `items[].x/y` are **device screen pixels** — the same space as a11y `bounds` `[left, top, right, bottom]`.

Instagram at `[128, 190, 240, 332]` → tap center `{"kind":"tap","x":184,"y":261}`.

Default screenshot JPEG is 0.5× MediaProjection (`captureW/H` on `/state`). That scale is **not** part of the tap API. Convert **only** if you clicked the image:

`x_api = x_image * screenW / captureW` (same for `y`). Hi-res JPEG (`hiRes` or snapshot with default `hiRes`) has multiplier 1 when `scale` is 1.

## Screenshot vs snapshot

**`screenshot`** — image only. `{"kind":"screenshot"}` JPEG 0.5× q45; `{"kind":"screenshot","hiRes":true}` full-res JPEG q70. Optional `scale` / `quality` override the mode defaults (and phone Status prefs). Then `GET /screenshot`. Do not expect a label list.

**`snapshot`** — same capture as screenshot, then the manager builds a ref list: parsed a11y nodes **plus** Tesseract (`eng`+`rus`). Default `hiRes: true`; `{"kind":"snapshot","hiRes":false}` uses the ordinary JPEG. OCR lines already named in a11y are skipped. Status/nav chrome is skipped. If OCR fails, a11y items are still returned (`ocrError`).

Consumers of snapshot get **only** the `snapshot` object — not raw `a11yJson`. Image is still `GET /screenshot`.

`GET`/`POST /snapshot`:

```json
{
  "snapshot": {
    "source": ["a11y", "ocr"],
    "captureW": 480, "captureH": 960, "screenW": 480, "screenH": 960,
    "ocrCount": 3,
    "items": [
      { "ref": "e1", "source": "a11y", "name": "Instagram", "x": 184, "y": 261 },
      { "ref": "e2", "source": "ocr", "name": "Gmail", "x": 90, "y": 400 }
    ]
  }
}
```

After execute, the same object is `/state.lastSnapshot`. First OCR call may download language data (slow).

## Files (desktop → phone)

Tunnel multipart already splits large encrypted payloads (HTTP adapter window 256 KiB). The phone command is still one JSON slot:

```bash
curl -s -X POST "$BASE/tunnels/$ID/queue" -H 'Content-Type: application/json' \
  -d "{\"kind\":\"file\",\"name\":\"note.txt\",\"mime\":\"text/plain\",\"data\":\"$(printf hi | base64 -w0)\"}"
curl -s -X POST "$BASE/tunnels/$ID/queue/execute"
# → /state.lastPutFile.path  e.g. /data/user/0/pro.potoki.bekon/files/inbox/note.txt
```

Reply from the phone (inside the execute batch slot): `{ "id", "ok": true, "type": "putFile", "path", "uri", "name", "size", "mime", "publicPath"? }`. `path` is always readable by Bekon. Public Downloads is optional (`publicPath`) and uses a unique display name if `app-debug.apk` is already taken in MediaStore. Max **25 MB**. Body limit on phone-control-api is **40 MB** (base64 overhead).

**APK update:** send `name` ending in `.apk` (or mime `application/vnd.android.package-archive`) with Status **Auto-update APK** on. The `putFile` ACK is sent first. Then: rooted devices `pm install -r` from a copy of the private file, Magisk overlay, reboot **only if install succeeded**; unrooted devices open the system installer (tap Continue). A later tunnel message `{ "type": "apkUpdate", "stage", "detail", "rooted" }` reports the outcome. First install of this APK on Motorola still needs one manual/system install so the new updater is present.

Remote push: `./apps/android-gateway/scripts/deploy update` (running tunnel + Auto-update APK).

## Logs (on request)

Status checkbox **Share logs on request** (default on). Then:

```bash
curl -s -X POST "$BASE/tunnels/$ID/queue" -H 'Content-Type: application/json' \
  -d '{"kind":"logs","n":80}'
curl -s -X POST "$BASE/tunnels/$ID/queue/execute"
# → /state.lastLogs  { adapter, messages, core, apkUpdate }
```

Disabled on the phone → execute error `logs sharing disabled`.

## Share (open a file in another app)

Do **not** scrape Instagram’s gallery / “New post” UI to attach a photo. Push the bytes, then share them.

```bash
# file then share in one batch
DATA=$(base64 -w0 /tmp/shot.jpg)
curl -s -X POST "$BASE/tunnels/$ID/queue" -H 'Content-Type: application/json' \
  -d "[{\"kind\":\"file\",\"name\":\"shot.jpg\",\"mime\":\"image/jpeg\",\"data\":\"$DATA\"},{\"kind\":\"share\",\"mime\":\"image/jpeg\",\"package\":\"com.instagram.android\"}]"
curl -s -X POST "$BASE/tunnels/$ID/queue/execute"
```

Explicit path:

`{"kind":"share","path":"/storage/emulated/0/Download/shot.jpg","mime":"image/jpeg","package":"com.instagram.android"}`

Omit `package` to show the Android share sheet. Phone command: `{ "cmd":"share", "id", "path?", "uri?", "mime?", "package?" }`.

Then snapshot again — Instagram’s composer is a new screen; previous tap coords are stale.

## Errors

- `409` — execute already in flight; wait and retry, or poll `/state` (`busy` / `executing`).
- `lastError` on `/state` — joined queue errors.
- Empty `GET /snapshot` → `404` until a successful `snapshot` command.

## UI (humans)

Phone tab defaults to **Immediate**: clear → enqueue (gestures also get sleep + screenshot; Hi-res mode uses `hiRes`) → execute. **Queue**: enqueue only. Capture is one split button: Screenshot / Screenshot Hi-res / Snapshot. The chevron menu also has **scale / JPEG quality** for preview and hi-res, and **Snapshot: hi-res | ordinary**. **File** sends `kind: file`. **Logs** sends `kind: logs`. Phone Status tab stores the same defaults if a command omits `scale`/`quality`.

## If an agent says the API is missing

These claims are wrong or outdated. Use the queue kinds above.

**“Execute returned the screen tree so I can tap from it.”**  
Only if the execute JSON has **`snapshot`**. That field is omitted unless this batch **ended** with a successful `kind: snapshot` (`sleep` / `ping` after it are fine). `[snapshot, tap]` and tap-only execute have **no** `snapshot`. `/state.lastSnapshot` and `GET /snapshot` are the UI/cache copy and may predate the tap. After every tap, nav, toast, or sheet, take a **new** snapshot.

**“`longPress` is unsupported / hangs the queue.”**  
`{"kind":"longPress","x":184,"y":261}` is a first-class kind: hold then lift (same pixel space as tap). Use it for context menus. The phone MCP server still **does not send** `kind: longPress` — on this tunnel it has hung the queue; MCP hold is `drag` + sleep + `release`.  
To **move** something (icon, list row, slider): do **not** follow `longPress` with a later `swipe` — that is two gestures; the finger is already up. Put this in **one** execute batch:

```json
[
  {"kind":"drag","x":184,"y":261},
  {"kind":"swipe","x1":184,"y1":261,"x2":320,"y2":261},
  {"kind":"swipe","x1":320,"y1":261,"x2":320,"y2":400},
  {"kind":"release"}
]
```

`swipe` after `drag` continues the same pointer. Several swipes are fine. `release` lifts. Do **not** fake a long-press with `swipe` where `x1==x2` and `y1==y2`. Some launchers still ignore a11y drags until their own edit mode; the API is still `drag` → `swipe`* → `release`, not ADB `sendevent`.

**“I need `elementId` / resource-id to tap.”**  
There is no `elementId` API. Take a `snapshot`, pick `items[].ref`, tap that item’s `x`,`y`. After **every** navigation, toast, or sheet, take a **new** snapshot. Do not reuse coords from an old tree.

**“Toasts / snackbars shift my taps.”**  
The overlay moved the screen. `sleep` 800–1500 ms, then `snapshot` again. Do not keep tapping the old `x`,`y`.

**“There is no keyboard / no way to submit.”**  
Type with `{"kind":"input","text":"..."}` into the focused field. Submit with `{"kind":"key","key":"enter"}`. Other keys: `backspace` (`n` repeats), `selectAll`, `clear`, `copy`, `cut`, `paste`.  
IME injection (`inputMode: "keys"`) only works if the user selected **Bekon Keys** as the keyboard. The APK cannot silently switch IMEs (no `WRITE_SECURE_SETTINGS`). If `input` returns `no_input`, tap the field first, snapshot, then input.

**“I have to walk Instagram’s gallery like MacroDroid.”**  
Don’t. `file` the image, then `share` with `"package":"com.instagram.android"`. That is `ACTION_SEND` + `EXTRA_STREAM`, not a UI script. `file` then `share` in the **same** execute batch is fine: the phone shares the file it just saved.

**“Taps from the screenshot JPEG.”**  
Queue taps are **device pixels**, not JPEG pixels. Prefer snapshot `items[].x/y`. Convert JPEG clicks only with `x * screenW / captureW`.
