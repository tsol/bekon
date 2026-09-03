<div align="center">

<img src="docs/bekon-icon.png" alt="Bekon" width="128">

# Bekon Suite · Be Konnected

**WLYA ў праводзе. Bekon у пакоі.**

[English](README.md) · [Русский](README.ru.md) · [Українська](README.uk.md) · **Беларуская** · [中文](README.zh.md) · [فارسی](README.fa.md)

</div>

Свой relay — свой endpoint. Прыклады хостаў у даках ілюстратыўныя.

---

## Сцэнарыі

Навошта гэта. Падрабязней і што ў планах: [`docs/USE-CASES.md`](docs/USE-CASES.md) (англ.).

### 1. Эмігранцкі шлюз

Пакідаючы радзіму, пакінь у бабулі рутаваны Android з мясцовай SIM — тэлефануй і прымай званкі праз яго. Ваенкамат, МУС, ДВР — you name it.

**Інструкцыя:** [`docs/guides/GUIDE-LINE.md`](docs/guides/GUIDE-LINE.md)

### 2. Дай сваему агенту тэлефон

Дай агенту тэлефон — падключы MCP, і ён зможа валодаць ім: націскаць, свайпіць, запускаць прыкладанні, плаціць тваёй карткай, думскроліць і тупіць у Instagram.

**Інструкцыя:** [`docs/guides/GUIDE-CONTROL.md`](docs/guides/GUIDE-CONTROL.md)

### 3. Зрабі са старога Android разумную калонку

Звольні Алісу і Алексу — размаўляй з Hermes напрамую.

**Інструкцыя:** [`docs/guides/GUIDE-SPEAKER.md`](docs/guides/GUIDE-SPEAKER.md)

### 4. White List Your Ass

Выкарыстоўвай WLYA-тунэль, каб кіраваць тэлефонам праз email, Excel, месенджар MAX на паркоўцы і любой кастомны адаптар. Дадавай колькі заўгодна запасных адаптараў, каб не страціць сувязь з радзімай. White List Your Ass!

**Інструкцыя:** [`docs/guides/GUIDE-WLYA.md`](docs/guides/GUIDE-WLYA.md)

---

## Прадукты

| Пласт | Назва | Роля |
|-------|-------|------|
| Транспарт | **WLYA Tunnel** | HMAC-канал, адаптары, duty failover. **White List Your Ass.** У пратаколе `seed`; у UI — **Secret**. |
| Аддалены UI | **Bekon Control** | Экран, жэсты, файлы, MCP. Поўны Gateway APK (`pro.potoki.bekon`). |
| GSM / голас | **Bekon Line** | Дамашняя SIM як якар. Кліент: **Bekon Phone**. |
| Парасон | **Bekon Suite** | Правод + прылада ў пакоі. Слоган: Be Konnected. |

**Line (голас)**

```
              ┌───────────────────────────┐
              │ тэлефон у кішэні,          │
              │ ты ў Грузіі                 │
              └─────────────┬─────────────┘
                            │
              ┌─────────────▼─────────────┐
              │ wlya relay                │
              │ (websocket-люстэрка)      │
              └─────────────┬─────────────┘
                            │
              ┌─────────────▼─────────────┐      ┌─────┐
              │ бабуліны стары android    │ ──→  │ GSM │
              └───────────────────────────┘      └─────┘
```

**Control (агент)**

```
        агент ──→ ┌───────────┐ ←── або ты сам кіруеш
                  │ phone-mcp │
                  └─────┬─────┘
                        │
              ┌─────────▼─────────┐
              │ imap, smtp, wlya —│
              │ любы канал сувязі │
              └─────────┬─────────┘
                        │
              ┌─────────▼─────────┐
              │ бабуліны стары    │
              │ android           │
              └───────────────────┘
```

Пратакол: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md), [`docs/PROTOCOL.md`](docs/PROTOCOL.md).

---

## Хуткі старт

```bash
git clone https://github.com/tsol/bekon.git && cd bekon

npm run install:all
npm run demo
npm run relay:compose
npm run stack:start
npm run gateway:deploy
```

Адкрый URL з `npm run stack:status` (часта `http://127.0.0.1:5174`).

**Усе каманды:** [`docs/COMMANDS.md`](docs/COMMANDS.md). **Па сцэнарыях:** [`docs/guides/GUIDES.md`](docs/guides/GUIDES.md).

---

## Карта рэпазіторыя

```
bekon/
├── apps/desktop-ui/
├── apps/wlya-tunnel/
├── apps/phone-control-api/
├── apps/phone-control-mcp/
├── apps/android-gateway/
├── apps/android-phone/
├── packages/
├── scripts/
├── tools/
└── docs/
```

---

## Дакументацыя

| Дак | Змест |
|-----|--------|
| [`docs/USE-CASES.md`](docs/USE-CASES.md) | Чатыры сцэнарыі |
| [`docs/guides/GUIDES.md`](docs/guides/GUIDES.md) | Гайды |
| [`docs/COMMANDS.md`](docs/COMMANDS.md) | npm-скрыпты |

---

Ліцэнзія [AGPL-3.0-or-later](LICENSE). Гл. [CONTRIBUTING.md](CONTRIBUTING.md) і [SECURITY.md](SECURITY.md).
