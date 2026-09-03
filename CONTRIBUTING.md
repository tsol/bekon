# Contributing

Thanks for helping improve Bekon Suite. This repo is a monorepo: Kotlin core, Android apps, Vue desktop-ui, Node services, and Python MCP tooling.

## Prerequisites

- **JDK 21** for Gradle (`wlya-core`, `wlya-tunnel`, Android modules)
- **Android SDK** for APK builds (`ANDROID_HOME` or `local.properties` with `sdk.dir`)
- **Node.js** (npm) for desktop-ui and `phone-control-api`
- **Docker** (optional) for `wlya-server` relay via `docker compose`

## Build and test

From the repository root:

```bash
npm run install:all
npm run desktop-ui:build
npm run core:test
npm run android:build    # requires Android SDK
```

See [`docs/COMMANDS.md`](docs/COMMANDS.md) for the full script index.

## Local dev stack

```bash
npm run stack:start      # desktop-ui + wlya-tunnel :18080 + phone-control-api :18082
npm run relay:compose    # local Redis + relay (Docker)
```

Aliases: `dev:start`, `dev:stop`, `dev:status`.

Point tunnel and voice clients at **your** relay URL — see [`packages/wlya-server/README.md`](packages/wlya-server/README.md).

## ADB deploy

```bash
npm run gateway:deploy
npm run phone-app:deploy
```

`apps/android-gateway/scripts/deploy` and `apps/android-phone/scripts/deploy` resolve `adb` from `$ADB`, then `$ANDROID_HOME/platform-tools/adb`, then `$ANDROID_SDK_ROOT/platform-tools/adb`, then `PATH`.

## Docs

Write new documentation in **English**. Use cases and roadmap: [`docs/USE-CASES.md`](docs/USE-CASES.md). Architecture, protocol, Control API, and Line notes live under `docs/`.

## Pull requests

- Keep changes focused; match existing style in each area.
- Do not commit secrets, serial numbers, or personal device paths.
- Run relevant builds/tests before opening a PR.
