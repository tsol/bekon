# Платформа адаптеров: duty, Lua, каталог

Задумка продукта, не только текущий код. Ядро пишется сейчас — протокол и хост под эти фазы **добавляются**, а не обходятся костылями.

Смысл: большую часть времени туннель живёт через быстрый **wlya**. Почта и будущие каналы (Sheets, Telegram, кастомный email) — failsafe и самостоятельная работа, если основного пути нет. Новые транспорты пользователи подгружают **скриптами**, без пересборки APK.

---

## Фаза 0 — duty (сделано в core)

Нативные адаптеры: **wlya = primary**, остальные типы по умолчанию **backup**.

Правила:

- Effective primary (первый running `role=primary`, иначе первый backup) **никогда не sleeping**: обычный poll и send.
- Backup по умолчанию sleeping: poll `sleepPollMs ± sleepJitterMs` (~час), **send нет**.
- Чужой inbound (после `seenIds`, не echo наших send) на этом backup → он active.
- Active, пока с последнего чужого inbound не прошло `idleMs` (default 10 мин). Пустой poll сам по себе не усыпляет. Свои send idle не продлевают.
- `poll FAILED` на effective primary → все running backup сразу active (чтобы можно было слать стук, не ждать час). Когда primary снова ок и idle на backup истёк — backup спит.

Настройки в секции формы **Polling / role** (Vue + Android): `role`, `pollIntervalMs`, `sleepPollMs`, `sleepJitterMs`, `idleMs`.

Код: `AdapterDutyCoordinator`, хуки в `Tunnel` (delay recv, filter send, ingest, poll catch). UI: бейджи и countdown; Android снимок ~2 с только на вкладке Settings.

Кейс: wlya заблокировали, с desktop шлёшь по почте → в пределах ~часа телефон будит email → дальше полная скорость на этом канале, пока идут чужие пакеты.

---

## Фаза 1 — Lua-хост в core (и desktop, и Android)

В **оба** бинарника вшивается один нативный тип (рабочее имя `lua`): рантайм Lua + модули ниже. Скрипт — данные инстанса, не новый Gradle-модуль.

Контракт скрипта (хост вызывает):

```lua
function init(config) end
function poll(last_seq)  -- return { { seq = n, blob = bytes_or_b64 }, ... }
function send(seq, blob) end
```

Lua возит **уже готовый transport blob** (шифрование остаётся в `Tunnel`, как у email/wlya). Скрипт не реализует AES туннеля.

Манифест инстанса (config + отдельные поля, точная схема на реализации):

- `script` — исходник Lua (или ссылка + кэш)
- `host_api` — версия поверхности (v1)
- пользовательские поля формы
- `role` / таймеры duty — как у всех адаптеров

Без JAR/DexClassLoader. Подгрузка на лету = текст скрипта + config.

Движок: один и тот же на JVM и Android (например Luaj / согласованный Lua 5.x). Версия API замораживается: новый модуль = bump `host_api`.

---

## Фаза 2 — поверхность Lua v1

Критерий победы: на этой поверхности **без патча Kotlin** пишутся три референса:

1. Telegram bot channel  
2. Google Sheets channel  
3. Custom email (в т.ч. payload в jpeg-аттаче)

### Модули v1

| Модуль | Зачем |
|--------|--------|
| `http.request{ method, url, headers, body, timeout }` | Telegram, Sheets, OAuth refresh. Ответ `{ status, headers, body }`. Нужен **multipart** (sendDocument). |
| `json.encode` / `json.decode` | оба HTTP-канала |
| `config` | read-only поля формы / advertise |
| `kv.get/set/del` | persist на инстанс: offset, cursor, refresh_token, seen mail ids |
| `log` | тот же adapter log |
| `util` | `now_ms`, `b64`, `url_encode`, `sha256`, `hmac_sha256`, `random_bytes` |
| `mail.fetch` / `mail.send` / `mail.delete` | обёртка JavaMail: сырой RFC822 или parts `{ mime, name, bytes }`, вложения в fetch. Не IMAP по TCP в Lua. |
| `image.jpeg_container(payload)` | валидный крошечный JPEG + груз (например после EOI) |

**Не публиковать в v1:** сырой `tcp`, WebSocket, браузерный OAuth внутри Lua, LSB-стего, HTML-парсер, полные Google/Telegram SDK.

### Auth

Отдельного туннельного события `adapter.auth` **нет**. Секреты — обычный config/kv.

Интерактив (Google login) — только desktop Vue по манифесту скрипта (`auth: google | none | …`): кнопка пишет `refresh_token` в config. Скрипт при 401 сам refresh через `http`. Телефон браузерный OAuth не открывает.

### Как садятся три канала

| | poll | send | секреты |
|---|------|------|---------|
| Telegram bot | `getUpdates`, offset в kv | `sendMessage` / `sendDocument` | bot token в config |
| Sheets | `values.get`, cursor в kv | `values.append` | refresh в config → kv |
| Custom email | `mail.fetch` + jpeg/аттач | `mail.send` + `image.jpeg_container` | imap/smtp в config |

Нативный `email` остаётся простым `[TUNNEL]`. Стего-почта — отдельный lua-скрипт на том же ящике.

---

## Фаза 3 — каталог, Vue, advertise на телефон

Флоу:

1. Публичное место (скрипты после ревью агентами AI).  
2. Пользователь в desktop Vue загружает адаптер (файл / каталог).  
3. Generic-форма из **схемы манифеста** (не новый `Form.vue` на каждый скрипт) + секция duty + опциональный Google login.  
4. Advertise по **уже живому** каналу (wlya/email) на Android.  
5. Телефон ставит инстанс (тот же `lua`-хост, новый script+config).

Это не «неизвестный Kotlin-класс». Хост уже в APK; неизвестен только скрипт.

Протокол доставки можно вырастить из нынешнего `advertise-adapters` (`AdapterInstanceConfig` + config-строки) или заменить отдельными командами install/ack. Суть: уезжают script + config, не JAR.

`upsertAdapter` / реестр должны принимать lua-инстансы (сейчас неизвестный `type` тихо отбрасывается — это меняется: тип хоста известен, тело скрипта в config).

### Duty после доставки

После install lua по умолчанию backup/sleeping. Advertise пришёл по wlya — новый канал сам не становится путём send.

Чтобы сразу работать через Sheets/Telegram:

- явное wake / Start / временно primary, **или**
- правило координатора: «только что установлен — active N минут», **или**
- событие wake с desktop.

Иначе desktop продолжит send только в active wlya, а lua на телефоне спит до часа / чужого inbound.

Размер: короткий `.lua` + токены как обычное туннельное сообщение (multipart уже есть). Первый пролив — по wlya, не через email TTL.

Android UI: generic renderer из той же schema; снимок duty как в фазе 0 (редко, только видимый Settings).

---

## Фаза 4 — референс-скрипты и каталог

В репо (или каталоге), без нового Kotlin:

- `telegram.lua` — bot API  
- `sheets.lua` — очередь строк  
- `email-stego.lua` — jpeg-аттач  

Если один из трёх требует ещё один примитив хоста — v1 не закрыта, добавляем модуль и bump `host_api`.

Потом: публикация, версии скриптов, `host_api` в манифесте («телефон слишком старый»).

---

## Сознательно не делаем (пока)

- Подгрузка JAR/DEX как основной dynamic path (desktop JAR ≠ Android ART; advertise бинарника на телефон — другой продукт).  
- `adapter.auth` как тип сообщения туннеля.  
- Lua вместо нативных wlya и email.  
- Полный пользовательский Telegram (MTProto).  
- Рынок неподписанного нативного кода внутри одного APK.

JAR позже только если Lua упрётся в SDK; это отдельный канал поставки, не advertise.

---

## Порядок работ

0. Duty — **есть**.  
1. Lua host + модули v1 (`http`, `json`, `kv`, `util`, `mail`, `image`) + тип `lua` в registry обоих клиентов.  
2. Generic form + schema + опциональный Google OAuth в Vue (результат в config).  
3. Доставка script+config на Android (advertise/install) + стык с duty (wake после install).  
4. Три референс-скрипта как критерий готовности поверхности.

Связанный код фазы 0: `wlya-core/.../AdapterDutyCoordinator.kt`, `Tunnel.kt`, `wlya-adapters/common/ui-vue/DutySection.vue`, `wlya-adapters/common/ui-android/AdapterDutySection.kt`.
