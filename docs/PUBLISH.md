# Publishing Bekon to GitHub

Canonical remote: **[`tsol/bekon`](https://github.com/tsol/bekon)** (`origin` → `git@github.com:tsol/bekon.git`). Branch: `main`.

This working copy tracks that public history (orphan root commit). Do **not** attach or force-push the old `phone-agent` commit graph onto `bekon`.

## First public snapshot (already done)

The initial GitHub `main` is a **fresh history**: a tree snapshot without the private clone’s commits (lab serials, old APKs, and other sensitive data that lived in git history).

## What not to do

| Action | Why |
|--------|-----|
| `git push --all` / `git push --mirror` from an old `phone-agent` clone | Uploads full private history to the public repo. |
| `gh repo create --source=.` from a clone that still has private objects | Same risk. |
| Re-adding `git@github.com:tsol/phone-agent.git` as `origin` | Easy to push the wrong refs; keep `origin` on `tsol/bekon` only. |
| Publishing `root-phone/itel-a27/`, lab `DEVICE.md`, or `.wlya/` | Lab brick data and live channel config — see `docs/PUBLIC-RELEASE.md`. |

## License and naming

- License at repo root: **AGPL-3.0** (`LICENSE`).
- Public repo: **`bekon`** (Bekon Suite monorepo).
- Product names: WLYA Tunnel, Bekon Control, Bekon Line — see `docs/BRAND.md`.

## CI

`.github/workflows/ci.yml`:

- **Node:** `npm ci`, `npm run build` (Vue workbench).
- **JDK 21:** `./gradlew :wlya-core:test --project-cache-dir .gradle-ci` (no Android SDK, no USB).
- **Docker:** `docker compose -f packages/wlya-server/docker-compose.yml config`.

Android `assembleDebug` is not in CI.

## After first public tag

See **Phase 6** in `docs/PUBLIC-RELEASE.md` (Play flavors, separate deploy docs, site, npm MCP package).
