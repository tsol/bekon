# WLYA UI + phone-client refactor (agent brief)

> **Audience:** coding agent. Follow phases in order. Do not invent new backend phone APIs. Do not change Docker `pnpm dev:start` contract. Android APK protocol stays as-is.

## Goal

1. **Tunnels tab** = full UI for `wlya-core` (AppManager / Tunnel / adapters / messages / debug / adapter-log). Tree: tunnels → adapters; create with `+`; **adapter logs always visible**.
2. **Phone** = 100% frontend TypeScript in `src/phone-client/`. Server must not know about phones. Transport = existing tunnel `send` + `messages` only.
3. **Server** = only wlya-core HTTP façade. Delete all `/api/tunnels/{id}/phone/*` routes.

## Non-goals / do not touch

- `scripts/dev-start.mjs` / `dev:stop` / `dev:status` / `dev:restart` Docker contract
- Kotlin `wlya-core` business logic (unless a tiny API gap blocks UI)
- Android command protocol in `IntentHandler.kt`
- Adding WebSocket/SSE (use polling)

## Implementation checklist

1. [x] Write `docs/REFACTOR-UI-PHONE.md` (this brief)
2. [x] Delete phone routes from `ApiServer.kt`
3. [x] Add `src/core-api/*` typed client
4. [x] Build `TunnelWorkbench` tree + LogPane on `/tunnels`
5. [x] Add `src/phone-client/**` transport + services + protocol
6. [x] Rebuild Phone UI on phone-client; remove old `/phone` fetch callers
7. [x] Nav/router fixes; delete dead components
8. [x] Docs + `pnpm build` + smoke

## Acceptance criteria

- Server has **zero** phone-specific endpoints
- Tunnels UI is a **tree** with create-`+` for tunnels and adapters
- Adapter **logs visible** without hunting (Log pane, polled `adapter-log`)
- All core HTTP ops used via `WlyaCoreClient`
- All phone logic under `src/phone-client` talking only `send`/`messages`
- Phone UI usable: correct coords, ping latency, non-silent gesture errors
- Docker scripts unchanged

See `.cursor/plans/wlya_ui_core_refactor_85e560ff.plan.md` for full phase details.
