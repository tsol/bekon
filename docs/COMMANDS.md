# Commands

Canonical CLI for the monorepo. All root commands run from the repository root:

```bash
npm install
npm run install:all   # also phone-control-api deps
```

Shell scripts live under `apps/`, `packages/`, `root-phone/`, and `tools/`. `package.json` is the index.

**`apps/`** — runnable/deployable components (UI, APKs, laptop services). **`packages/`** — shared libraries and relay infra.

---

## desktop-ui (browser shell)

| Command | Action |
|---------|--------|
| `npm run desktop-ui:dev` | Vite dev server |
| `npm run desktop-ui:build` | Production build → `apps/desktop-ui/dist/` |
| `npm run desktop-ui:preview` | Preview production build |

Aliases: `npm run dev`, `npm run build`, `npm run preview`.

---

## Dev stack (`npm run stack:start`)

Three local services:

| Service | Package | Port | Role |
|---------|---------|------|------|
| **desktop-ui** | `apps/desktop-ui` | 5174 | Vue UI — Tunnels / Control / Voice tabs |
| **wlya-tunnel** | `apps/wlya-tunnel` | 18080 | JVM tunnel host — REST `/api/tunnels`, adapters |
| **phone-control-api** | `apps/phone-control-api` | 18082 | Control queue HTTP API (gestures, screenshots) |

| Command | Action |
|---------|--------|
| `npm run stack:start` | Start all three |
| `npm run stack:start -- --bg` | Background |
| `npm run stack:stop` | Stop |
| `npm run stack:status` | Ports and health |
| `npm run stack:restart` | Restart |

Aliases: `dev:start`, `dev:stop`, `dev:status`, `dev:restart`.

---

## Gateway APK (Bekon Control on the device)

| Command | Action |
|---------|--------|
| `npm run gateway:build` | `./gradlew :android-client:app:assembleDebug` |
| `npm run gateway:deploy` | USB `adb install` debug APK |
| `npm run gateway:deploy:magisk` | Root: Magisk priv-app overlay + reboot |
| `npm run gateway:fix-adb` | Install Linux udev rules (sudo, once) |
| `npm run gateway:update` | Build + push APK via tunnel putFile (`phone-control-api`) |
| `npm run gateway:uplink` | Tiny HTML → phone Downloads (`UPDATE_PAGE=…`) |

Tunnel id (pick one):

```bash
# direct script — --tunnel on the deploy script
./apps/android-gateway/scripts/deploy update --tunnel phone-1
./apps/android-gateway/scripts/deploy uplink --tunnel phone-1 --  # see script: UPDATE_PAGE env for uplink URL

# via npm — extra args after --
npm run gateway:update -- --tunnel phone-1
npm run gateway:uplink -- --tunnel phone-1
```

`phone-1` is a substring of tunnel `id` or `label` from `curl -s http://127.0.0.1:18082/tunnels`. Legacy fallback: env `TUNNEL=phone-1` (same as `--tunnel`).

Script: `apps/android-gateway/scripts/deploy`.

---

## Bekon Phone (Line client APK)

| Command | Action |
|---------|--------|
| `npm run phone-app:build` | `./gradlew :bekon-phone:assembleDebug` |
| `npm run phone-app:deploy` | USB install + optional voice prefs |

Script: `apps/android-phone/scripts/deploy`.

---

## Relay (wlya-server)

| Command | Action |
|---------|--------|
| `npm run relay:compose` | Docker Compose locally |
| `npm run relay:start` | Host dev Node on `PORT` (default `18081`) |
| `npm run relay:stop` | Stop host Node |
| `npm run relay:status` | PID, port, `/health` |
| `npm run relay:deploy` | Rsync + `docker compose` on remote VPS |
| `npm run relay:publish-apk` | Copy debug APK to remote `public/bekon.apk` (`/u`) |

Remote deploy: copy `.env.deploy.example` → `.env.deploy` (gitignored).

---

## phone-control-api and phone-control-mcp

| Command | Action |
|---------|--------|
| `npm run phone-control-api:dev` | tsx watch `:18082` |
| `npm run phone-control-api:build` | `tsc` |
| `npm run phone-control-mcp` | Python MCP on `:18083` |

---

## Gradle / Android

| Command | Action |
|---------|--------|
| `npm run core:test` | `:wlya-core:test` |
| `npm run android:build` | Gateway + Phone debug APKs |

---

## Lab (`root-phone/`)

| Command | Action |
|---------|--------|
| `npm run lab:status` | Root / Magisk check |
| `npm run lab:recon -- motorola` | Device recon dump |
| `npm run lab:dump-mixer -- redmi` | Mixer dump during call |
| `npm run magisk:pack` | Zip Magisk module |

---

## Demo

| Command | Action |
|---------|--------|
| `npm run demo` | Interactive wizard → `.demo/session.json` |

---

## CI

| Command | Action |
|---------|--------|
| `npm run ci` | desktop-ui build + core tests + phone-control-api build |
