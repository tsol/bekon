# Publishing Bekon to GitHub (first public repo)

This clone may contain secrets, lab serials, old APKs, or other sensitive data **in git history**. Do **not** push this repository’s full history to a public remote.

Keep this working copy (or a private mirror) as the archive with full history. Publish only a **fresh history** on GitHub.

## Recommended: orphan branch → new empty repo

From the **current tree** (after hygiene checks in `docs/PUBLIC-RELEASE.md`), create a branch with no parents:

```bash
cd /path/to/bekon

# Optional: final grep for paths, serials, live secrets
# git grep -i 'serial\|/home/harry\|VOICE_SEED' -- ':!root-phone/'

git checkout --orphan public
git add -A
git commit -m "Initial public release of Bekon Suite"
```

Create an **empty** repository on GitHub named `bekon` (via the web UI — do **not** use `gh repo create` with `--source` from this clone, which would upload existing history).

Add the public remote and push **only** the orphan branch:

```bash
git remote add public git@github.com:tsol/bekon.git
git push -u public public:main
```

After the first push:

1. Enable **Actions** in the GitHub repo settings (workflow: `.github/workflows/ci.yml`).
2. Tag releases from the public repo when ready (`git tag v0.1.0 && git push public v0.1.0`).

Return this private clone to your normal branch; do not merge the orphan branch back into development history unless you intend to squash everything.

## What not to do

| Action | Why |
|--------|-----|
| `git push --all` / `git push --mirror` from this clone | Uploads full history, including removed secrets and binaries. |
| `gh repo create --source=.` | Same risk: attaches existing commits. |
| `git filter-repo` on the **only** copy | Destructive rewrite with no backup; use orphan + private mirror instead. |
| Publishing `root-phone/itel-a27/`, lab `DEVICE.md`, or `.wlya/` | Lab brick data and live channel config — see `docs/PUBLIC-RELEASE.md`. |

## License and naming

- License at repo root: **AGPL-3.0** (`LICENSE`).
- Suggested public repo name: **`bekon`** (Bekon Suite monorepo).
- Product names in docs: WLYA Tunnel, Bekon Control, Bekon Line — see `docs/BRAND.md`.

## CI on the public repo

The orphan push includes `.github/workflows/ci.yml`:

- **Node:** `npm ci`, `npm run build` (Vue workbench).
- **JDK 21:** `./gradlew :wlya-core:test --project-cache-dir .gradle-ci` (no Android SDK, no USB).
- **Docker:** `docker compose -f packages/wlya-server/docker-compose.yml config` (validates relay stack YAML only).

Android `assembleDebug` is intentionally **not** in CI (requires SDK and is not needed for core/protocol validation).

## Private mirror workflow

| Clone | Role |
|-------|------|
| This repo (full history) | Private development / forensic archive |
| GitHub `bekon` (orphan `main`) | Public source, issues, Actions, releases |

Sync **content** to public by repeating the orphan flow or cherry-picking squashed commits — never push private branch refs to `public`.

## Optional: `git archive` without lab trees

Root `.gitattributes` marks lab paths with `export-ignore`. Archives omit them:

```bash
git archive --format=tar.gz --prefix=bekon/ HEAD -o bekon-src.tar.gz
```

Review the tarball before publishing anywhere.

## After first public tag

See **Phase 6** in `docs/PUBLIC-RELEASE.md` (Play flavors, separate deploy docs, site, npm MCP package).
