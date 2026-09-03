# Bekon Suite — план публичного релиза

Канон имён: [`BRAND.md`](BRAND.md). Этот файл — чеклист миграции, не витрина продукта.

**Решение:** один публичный монорепо `bekon` (не четыре репы на старте). Hinge из продукта убрать полностью. Lab/brick/Hermes-пути — не в GitHub.

Перенос каталога: `wlya-tunnel` → `../projects/bekon` (git history сохранить). Старый путь — symlink на переходный период, если что-то ещё смотрит в `projects/wlya-tunnel`.

---

## Продукты (как рассказывать)

| Слой | Имя | Что это |
|------|------|---------|
| Транспорт | **WLYA Tunnel** | Неубиваемый канал (HMAC, адаптеры, relay). В UI/доках протокола — WLYA. Расшифровка White List Your Ass — не в README и не в Play. |
| Пульт | **Bekon Control** | Агент видит экран, жесты, MCP. Полный Gateway APK + phone-manager. |
| Якорь | **Bekon Line** | GSM-шлюз: рутованный телефон дома, симка родной страны, голос/набор. Клиент — Bekon Phone. |
| Зонтик | **Bekon Suite** · Be Konnected | Провод + устройство в комнате. |

Режимы одного устройства, не четыре бренда. Play — урезанный Bekon (туннель + рация). GitHub APK — полный Control.

Пакеты: устройство `pro.potoki.bekon.*`, ядро `com.wlya.core`. Поля протокола `seed` не переименовывать (ломает клиентов); в UI — Secret / Room.

---

## Почему монорепо

- `wlya-core` + codegen `wlya-adapters` кормят desktop и Gateway APK.
- `bekon-call` общий у Gateway Voice и Bekon Phone.
- `phone-manager` ходит в `wlya-desktop`.
- `/v1/call` в `wlya-server` — Voice tab, Gateway, Phone.
- Vue workbench — один shell: Tunnels / Control / Voice.

Резать позже, не в первом публичном теге: `wlya-server` (отдельный deploy), Play flavor Phone, npm/pip MCP.

---

## Что не публиковать

- `root-phone/itel-a27/**` — brick, PAC, spd_dump, абсолютные пути.
- DEVICE.md с серийниками, `my-harrys-moto`, лабораторные секреты.
- `.hinge/`, зависимость `hinge` (снято в фазе 0).
- `.dev/`, `.gradle-host/`, `.wlya/*.json` с живыми каналами.
- Firmware, Magisk APK в `root-phone/vendor/`.
- Handoff агентов (`AGENT-HANDOFF.md` и т.п.).
- Скиллы с `com.hermes.agent`.

Публично можно оставить Magisk `wlya-voice` + общие ADB-скрипты без lab serials.

Приватный `bekon-lab`: itel recovery, серийники, direnv с `VOICE_SEED`.

---

## Целевое дерево

```
bekon/
  README.md
  LICENSE
  SECURITY.md
  CONTRIBUTING.md
  docs/
    BRAND.md
    PUBLIC-RELEASE.md      # этот файл
    ARCHITECTURE.md
    PROTOCOL.md            # HMAC, seed vs secret, /v1/inbox, /v1/call
    control-protocol.md    # бывший PHONE-CONTROL.md
    line.md                # бывший VOICE-PLAN.md (сжатый)
  apps/
    workbench/             # src/ + Vite
    gateway/               # android-client
    phone/                 # bekon-phone
  packages/
    wlya-core/
    wlya-adapters/
    wlya-desktop/
    wlya-server/
    bekon-call/
    phone-manager/
    phone-mcp/             # из phone-manager/mcp
  tools/
    adb/gateway            # бывший from-host.sh
    adb/phone              # бывший from-host-bekon-phone.sh
    magisk/wlya-voice/
  scripts/                 # dev-start/stop, udev
```

Скрипты (имена `from-host*` снаружи бессмысленны):

| Сейчас | Цель |
|--------|------|
| `from-host.sh` | `tools/adb/gateway` |
| `from-host-bekon-phone.sh` | `tools/adb/phone` |
| `from-host-wlya-server.sh` | `packages/wlya-server/scripts/relay` |

ADB: `PATH` / `ANDROID_HOME`, не `/home/harry/Android/Sdk`. Room/secret только env.

---

## Фазы

### Фаза 0 — Hinge

- [x] Решение: Hinge не входит в продукт.
- [x] Удалить `"hinge": "file:../hinge"` из `package.json` / lockfile.
- [x] Удалить tracked `.hinge/` из git; `.hinge/` в `.gitignore`.
- [x] `npm run build` без `../hinge` (проверка при удобстве).

### Фаза 1 — гигиена на месте (ещё `wlya-tunnel`)

Не двигать папки. Проверить: `./gradlew :wlya-core:test`, оба `assembleDebug`, `npm run build`.

- [x] ADB-скрипты: `ANDROID_HOME` / `command -v adb`, без `/home/harry`.
- [x] MCP README: дефолт скринов `./shots`; Hermes `/opt/data` и `~/hermes` только если env, не в публичном тексте (или `docs/integrations/hermes.md` позже).
- [x] Переписать или удалить `phone-manager/docs/android-phone-gateway/` (`com.hermes.agent`).
- [x] `PHONE-CONTROL.md` → `docs/control-protocol.md`.
- [x] `VOICE-PLAN.md` → `docs/line.md` или archive.
- [x] Корневой README — три продукта, не только Gradle-карта.
- [x] LICENSE (решение: AGPL для suite vs MIT core — зафиксировать до GitHub).
- [x] SECURITY.md, CONTRIBUTING.md.
- [x] Дефолт call URL в APK пустой или плейсхолдер, не обязательный `wlya.potoki.pro` как единственный мир; пример домена в доках ок.
- [x] Grep: `/home/harry`, `~/hermes`, `my-harrys-moto`, серийники, `qwerty`.
- [x] `.gitignore`: `.wlya/`, `.hinge/`, firmware, dumps.

### Фаза 2 — переезд каталога

- [x] Каталог `../projects/bekon` (git history внутри).
- [x] `WLYA_DIR` в `hermes/run.sh` указывает на `projects/bekon` (fallback на `wlya-tunnel`).
- [x] Symlink `projects/wlya-tunnel` → `bekon`.

### Фаза 3 — раскладка `apps/` / `packages/` / `tools/`

- [x] Один большой `git mv`, чтобы blame жил. Обновить `settings.gradle.kts`, Vite aliases, codegen, CI.

### Фаза 4 — витрина

- [x] README: Tunnel / Control / Line + схема бабушка ↔ relay ↔ трубка/ноут.
- [x] `docs/ARCHITECTURE.md`.
- [x] Не светить внутреннюю расшифровку WLYA в карточке.

### Фаза 5 — GitHub

> Канон: [`https://github.com/tsol/bekon`](https://github.com/tsol/bekon). Старый приватный `tsol/phone-agent` не origin. См. [`PUBLISH.md`](PUBLISH.md).

- [x] Новый repo `bekon` (orphan `main` на GitHub).
- [x] Этот клон трекает публичный `main`; приватную историю на GitHub не пушить.
- [x] CI: `.github/workflows/ci.yml` — `wlya-core:test`, `npm run build`, `docker compose config` для relay; без Android SDK / USB (assemble в CI не включён).
- [ ] Flavors Play vs GitHub — не блокер первого тега, но не смешивать в README «удалённо жми бабушкин UI» и магазин.

### Фаза 6 — после первого публичного тега (не в этом прогоне)

Отложено специально: не смешивать с первым orphan-push.

- [ ] Play `applicationId` / flavor без a11y (см. BRAND.md)
- [ ] Вынести `wlya-server` в отдельный deploy-репо, если версии разъедутся
- [ ] Сайт Be Konnected
- [ ] Пакет `@bekon/mcp` / pip, если MCP ставят отдельно
- [ ] `root-phone/itel-a27` в приватный `bekon-lab`, не в public tree

---

## Риски

- Один `applicationId` + README про удалённое управление → Play.
- Root/GSM inject в доках: модуль и свой риск, без бутлоадер-эксплойтов itel.
- Git history с ключами — filter или squash.
- Не коммитить `build/`, `.gradle-host`.
- Протокол: `seed` в query/HMAC остаётся `seed`.

---

## Порядок исполнения (практика)

1. Фаза 0 (Hinge) — сразу, без переезда.
2. Фаза 1 — пока живём в `wlya-tunnel`.
3. Фаза 2 — `../projects/bekon`.
4. Фаза 3 — по желанию до или после первого public tag.
5. Фаза 5 — только после grep на секреты.

Не делать фазы 2–5 в одном коммите с незакрытой фазой 1.
