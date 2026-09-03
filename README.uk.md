<div align="center">

<img src="docs/bekon-icon.png" alt="Bekon" width="128">

# Bekon Suite · Be Konnected

[English](README.md) · [Русский](README.ru.md) · **Українська** · [Беларуская](README.be.md) · [中文](README.zh.md) · [فارسی](README.fa.md)

</div>

---

## Сценарії

Навіщо це. Детальніше й що в планах: [`docs/USE-CASES.md`](docs/USE-CASES.md) (англ.).

### 1. Емігрантський шлюз

Покидаючи Батьківщину, залиш у бабусі рутований Android з місцевою SIM — дзвони й приймай дзвінки через нього. Військкомат, МВС, ДБР — you name it.

**Інструкція:** [`docs/guides/GUIDE-LINE.md`](docs/guides/GUIDE-LINE.md)

### 2. Дай своєму агенту телефон

Дай агенту телефон — підключи MCP, і він зможе володіти ним: тапати, свайпити, запускати застосунки, платити твоєю карткою, думскролити й тупити в Instagram.

**Інструкція:** [`docs/guides/GUIDE-CONTROL.md`](docs/guides/GUIDE-CONTROL.md)

### 3. Зроби зі старого Android розумну колонку

Звільни Алісу й Алексу — спілкуйся з Hermes напряму.

**Інструкція:** [`docs/guides/GUIDE-SPEAKER.md`](docs/guides/GUIDE-SPEAKER.md)

### 4. White List Your Ass

Використовуй WLYA-тунель, щоб керувати телефоном через email, Excel, месенджер MAX на парковці й будь-який кастомний адаптер. Додавай скільки завгодно запасних адаптерів, щоб не втратити зв’язок із домом. White List Your Ass!

**Інструкція:** [`docs/guides/GUIDE-WLYA.md`](docs/guides/GUIDE-WLYA.md)

---

## Продукти

| Шар | Назва | Роль |
|-----|-------|------|
| Транспорт | **WLYA Tunnel** | HMAC-канал, адаптери, duty failover. **White List Your Ass.** У протоколі `seed`; в UI — **Secret**. |
| Віддалений UI | **Bekon Control** | Екран, жести, файли, MCP. Повний Gateway APK (`pro.potoki.bekon`). |
| GSM / голос | **Bekon Line** | Домашня SIM як якір. Клієнт: **Bekon Phone**. |
| Парасолька | **Bekon Suite** | Дріт + пристрій у кімнаті. Слоган: Be Konnected. |

**Line (голос)**

```
              ┌───────────────────────────┐
              │ телефон у кишені,           │
              │ ти в Грузії                 │
              └─────────────┬─────────────┘
                            │
              ┌─────────────▼─────────────┐
              │ wlya relay                │
              │ (websocket-дзеркало)      │
              └─────────────┬─────────────┘
                            │
              ┌─────────────▼─────────────┐      ┌─────┐
              │ бабусин старий android    │ ──→  │ GSM │
              └───────────────────────────┘      └─────┘
```

**Control (агент)**

```
        агент ──→ ┌───────────┐ ←── або ти сам керуєш
                  │ phone-mcp │
                  └─────┬─────┘
                        │
              ┌─────────▼─────────┐
              │ imap, smtp, wlya —│
              │ будь-який канал     │
              └─────────┬─────────┘
                        │
              ┌─────────▼─────────┐
              │ бабусин старий    │
              │ android           │
              └───────────────────┘
```

Деталі протоколу: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md), [`docs/PROTOCOL.md`](docs/PROTOCOL.md).

---

## Швидкий старт

```bash
git clone https://github.com/tsol/bekon.git && cd bekon

npm run install:all
npm run demo
npm run relay:compose
npm run stack:start
npm run gateway:deploy
```

Відкрий URL з `npm run stack:status` (часто `http://127.0.0.1:5174`). Адаптери й голос — на **свій** relay.

**Усі команди:** [`docs/COMMANDS.md`](docs/COMMANDS.md). **За сценаріями:** [`docs/guides/GUIDES.md`](docs/guides/GUIDES.md).

---

## Карта репозиторію

```
bekon/
├── apps/desktop-ui/
├── apps/wlya-tunnel/
├── apps/phone-control-api/
├── apps/phone-control-mcp/
├── apps/android-gateway/
├── apps/android-phone/
├── packages/wlya-core/
├── packages/wlya-adapters/
├── packages/wlya-server/
├── packages/bekon-call/
├── scripts/
├── tools/
└── docs/
```

---

## Документація

| Док | Зміст |
|-----|--------|
| [`docs/USE-CASES.md`](docs/USE-CASES.md) | Чотири сценарії та roadmap |
| [`docs/guides/GUIDES.md`](docs/guides/GUIDES.md) | Покрокові гайди |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Як пов’язані компоненти |
| [`docs/COMMANDS.md`](docs/COMMANDS.md) | npm-скрипти |

---

Ліцензія [AGPL-3.0-or-later](LICENSE). Див. [CONTRIBUTING.md](CONTRIBUTING.md) та [SECURITY.md](SECURITY.md).
