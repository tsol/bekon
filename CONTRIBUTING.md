# Contributing

Thanks for helping improve Bekon Suite. This repo is a monorepo: Kotlin core, Android apps, Vue workbench, Node services, and Python MCP tooling.

## Prerequisites

- **JDK 21** for Gradle (`wlya-core`, `wlya-desktop`, Android modules)
- **Android SDK** for APK builds (`ANDROID_HOME` or `local.properties` with `sdk.dir`)
- **Node.js** (npm) for the Vue workbench and `phone-manager`
- **Docker** (optional) for `wlya-server` relay via `docker compose`

## Build and test

From the repository root:

```bash
# Vue workbench
npm install
cd packages/phone-manager && npm install && cd ../..
npm run build

# Kotlin core tests (optional project cache dir keeps Gradle state out of ~/.gradle)
./gradlew :wlya-core:test --project-cache-dir "$PWD/.gradle-host"

# Android APKs (requires SDK)
./gradlew :android-client:app:assembleDebug :bekon-phone:assembleDebug \
  --project-cache-dir "$PWD/.gradle-host"
```

## Local dev stack

```bash
pnpm dev:start    # or npm run dev:start — Vite + wlya-desktop :18080 + phone-manager :18082
```

Relay (self-hosted):

```bash
cd packages/wlya-server
docker compose up -d --build
```

Point tunnel and voice clients at **your** relay URL — see [`packages/wlya-server/README.md`](packages/wlya-server/README.md).

## ADB deploy scripts

`tools/adb/gateway` and `tools/adb/phone` (root wrappers: `from-host.sh`, `from-host-bekon-phone.sh`) resolve `adb` from `$ADB`, then `$ANDROID_HOME/platform-tools/adb`, then `$ANDROID_SDK_ROOT/platform-tools/adb`, then `PATH`.

## Docs

- Product names: [`docs/BRAND.md`](docs/BRAND.md)
- Phone control HTTP API: [`docs/control-protocol.md`](docs/control-protocol.md)
- GSM / walkie voice line: [`docs/line.md`](docs/line.md)

## Pull requests

- Keep changes focused; match existing style in each area.
- Do not commit secrets, serial numbers, or personal device paths.
- Run relevant builds/tests before opening a PR.
