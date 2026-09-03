<div align="center">

<img src="docs/bekon-icon.png" alt="Bekon" width="128">

# Bekon Suite · Be Konnected

**WLYA в проводе. Bekon в комнате.**

[English](README.md) · **Русский** · [Українська](README.uk.md) · [Беларуская](README.be.md) · [中文](README.zh.md) · [فارسی](README.fa.md)

</div>

Свой relay — свой endpoint. Примеры хостов в доках иллюстративные.

---

## Сценарии

Зачем это. Подробнее и что в планах: [`docs/USE-CASES.md`](docs/USE-CASES.md) (англ.).

### 1. Эмигрантский шлюз

Покидая родину, оставь у бабушки рутованный Android с местной SIM — звони и принимай звонки через него. Военкомат, МВД, ФСИН — you name it.

**Инструкция:** [`docs/guides/GUIDE-LINE.md`](docs/guides/GUIDE-LINE.md)

### 2. Дай своему агенту телефон

Дай своему агенту телефон — подключи MCP, и он сможет обладать телефоном: тапать, свайпить, запускать приложения, платить твоей кредиткой, думскроллить и тупить в Instagram.

**Инструкция:** [`docs/guides/GUIDE-CONTROL.md`](docs/guides/GUIDE-CONTROL.md)

### 3. Сделай из старого Android умную голосовую колонку

Уволь Алису и Алексу — общайся с Hermes напрямую.

**Инструкция:** [`docs/guides/GUIDE-SPEAKER.md`](docs/guides/GUIDE-SPEAKER.md)

### 4. White List Your Ass

Используй WLYA-туннель, чтобы управлять телефоном через email, Excel, мессенджер MAX на парковке и через любой кастомный туннельный адаптер. Добавляй сколько угодно адаптеров про запас, чтобы не потерять связь с родиной. White List Your Ass!

**Инструкция:** [`docs/guides/GUIDE-WLYA.md`](docs/guides/GUIDE-WLYA.md)

---

## Продукты

| Слой | Имя | Роль |
|------|-----|------|
| Транспорт | **WLYA Tunnel** | HMAC-канал, адаптеры, duty failover. **White List Your Ass.** В протоколе `seed`; в UI — **Secret**. |
| Удалённый UI | **Bekon Control** | Экран, жесты, файлы, MCP. Полный Gateway APK (`pro.potoki.bekon`). |
| GSM / голос | **Bekon Line** | Домашняя SIM как якорь. Клиент: **Bekon Phone**. |
| Зонтик | **Bekon Suite** | Провод + устройство в комнате. Слоган: Be Konnected. |

**Line (голос)**

```
              ┌───────────────────────────┐
              │ телефон в кармане,        │
              │ ты в Грузии               │
              └─────────────┬─────────────┘
                            │
              ┌─────────────▼─────────────┐
              │ wlya relay                │
              │ (websocket-зеркало)       │
              └─────────────┬─────────────┘
                            │
              ┌─────────────▼─────────────┐      ┌─────┐
              │ бабушкин старый android   │ ──→  │ GSM │
              └───────────────────────────┘      └─────┘
```

**Control (агент)**

```
        агент ──→ ┌───────────┐ ←── или ты сам управляешь
                  │ phone-mcp │
                  └─────┬─────┘
                        │
              ┌─────────▼─────────┐
              │ imap, smtp, wlya —│
              │ любой канал связи │
              └─────────┬─────────┘
                        │
              ┌─────────▼─────────┐
              │ бабушкин старый   │
              │ android           │
              └───────────────────┘
```

Подробнее про протокол и компоненты: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md), [`docs/PROTOCOL.md`](docs/PROTOCOL.md).

---

## Быстрый старт

```bash
git clone https://github.com/tsol/bekon.git && cd bekon

npm run install:all
npm run demo              # мастер: relay + stack + channel/secret
# или вручную:
npm run relay:compose     # локально Redis + relay
npm run stack:start       # desktop-ui + wlya-tunnel :18080 + phone-control-api :18082
npm run gateway:deploy    # USB — нужны ANDROID_HOME / adb
```

Открой URL из `npm run stack:status` (часто `http://127.0.0.1:5174`). Адаптеры и голос — на **свой** relay.

**Все команды:** [`docs/COMMANDS.md`](docs/COMMANDS.md). **По сценариям:** [`docs/guides/GUIDES.md`](docs/guides/GUIDES.md).

**Android:** `npm run gateway:build`, `npm run phone-app:deploy`.

---

## Карта репозитория

```
bekon/
├── apps/desktop-ui/          Vue — Tunnels / Control / Voice
├── apps/wlya-tunnel/         JVM tunnel host REST :18080
├── apps/phone-control-api/   Control HTTP :18082
├── apps/phone-control-mcp/   MCP :18083
├── apps/android-gateway/     Gateway APK + magisk + deploy
├── apps/android-phone/       Bekon Phone — Line + deploy
├── packages/wlya-core/       Kotlin tunnel library
├── packages/wlya-adapters/   транспорты (wlyaserver, email, …)
├── packages/wlya-server/       Node + Redis relay
├── packages/bekon-call/      клиент WebSocket /v1/call
├── scripts/                  dev stack (stack:start, stack:stop)
├── tools/                    run.sh, demo wizard
└── docs/
```

---

## Документация

| Док | Содержание |
|-----|------------|
| [`docs/USE-CASES.md`](docs/USE-CASES.md) | Четыре сценария и roadmap |
| [`docs/guides/GUIDES.md`](docs/guides/GUIDES.md) | Пошаговые гайды (Line, Control, Speaker, WLYA) |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Как связаны компоненты, adapter duty |
| [`docs/PROTOCOL.md`](docs/PROTOCOL.md) | HMAC, seed vs Secret, endpoints relay |
| [`docs/CONTROL-PROTOCOL.md`](docs/CONTROL-PROTOCOL.md) | API очереди phone-control-api |
| [`docs/LINE.md`](docs/LINE.md) | Голос / GSM режимы A/B/C |
| [`docs/COMMANDS.md`](docs/COMMANDS.md) | npm-скрипты — build, deploy, relay, lab |
| [`packages/wlya-adapters/README.md`](packages/wlya-adapters/README.md) | Добавить адаптер |
| [`packages/wlya-server/README.md`](packages/wlya-server/README.md) | Деплой relay |

---

Лицензия [AGPL-3.0-or-later](LICENSE). См. [CONTRIBUTING.md](CONTRIBUTING.md) и [SECURITY.md](SECURITY.md).
