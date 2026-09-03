<div align="center">

<img src="docs/bekon-icon.png" alt="Bekon" width="128">

# Bekon Suite · Be Konnected

<p dir="rtl"><strong>WLYA در سیم. Bekon در اتاق.</strong></p>

[English](README.md) · [Русский](README.ru.md) · [Українська](README.uk.md) · [Беларуская](README.be.md) · [中文](README.zh.md) · **فارسی**

</div>

<div dir="rtl">

relay خودت — endpoint خودت. نام‌های میزبان در مستندات فقط مثال‌اند.

---

## سناریوها

چرا ساخته شده. جزئیات و نقشه‌راه: [`docs/USE-CASES.md`](docs/USE-CASES.md) (انگلیسی).

### ۱. دروازه مهاجرت

با ترک وطن، یک **Android روت‌شده با SIM محلی** پیش مادربزرگ (یا هر آدرس مطمئن) بگذار — تماس بگیر و بگیر **از همان گوشی**. سربازی، وزارت کشور، زندان، بانک‌ها — you name it.

**راهنما:** [`docs/guides/GUIDE-LINE.md`](docs/guides/GUIDE-LINE.md)

### ۲. به agentت یک گوشی بده

MCP را وصل کن تا **یک Android واقعی** داشته باشد: لمس، سوایپ، باز کردن اپ، پرداخت با کارت تو، doomscroll در اینستاگرام، ماندن در تنظیمات تا درست شود.

**راهنما:** [`docs/guides/GUIDE-CONTROL.md`](docs/guides/GUIDE-CONTROL.md)

### ۳. Android قدیمی را بزرگ‌کننده هوشمند کن

Alice و Alexa را اخراج کن — مستقیم با **Hermes** حرف بزن.

**راهنما:** [`docs/guides/GUIDE-SPEAKER.md`](docs/guides/GUIDE-SPEAKER.md)

### ۴. White List Your Ass

با تونل **WLYA** گوشی را از طریق **ایمیل**، **اکسل**، **MAX** یا هر آداپتور سفارشی کنترل کن — حتی در پارکینگ وقتی اینترنت «واقعی» قطع است. هرچقدر آداپتور پشتیبان می‌خواهی اضافه کن. White List Your Ass!

**راهنما:** [`docs/guides/GUIDE-WLYA.md`](docs/guides/GUIDE-WLYA.md)

---

## محصولات

| لایه | نام | نقش |
|------|-----|-----|
| حمل‌ونقل | **WLYA Tunnel** | کانال HMAC، آداپتورها، duty failover. **White List Your Ass.** در پروتکل `seed`؛ در UI **Secret**. |
| رابط دور | **Bekon Control** | صفحه، ژست، فایل، MCP. APK کامل Gateway (`pro.potoki.bekon`). |
| GSM / صدا | **Bekon Line** | SIM خانه به‌عنوان لنگر. کلاینت: **Bekon Phone**. |
| چتر | **Bekon Suite** | سیم + دستگاه در اتاق. شعار: Be Konnected. |

**Line (صدا)**

```
              ┌───────────────────────────┐
              │ گوشی در جیب،               │
              │ تو در گرجستان              │
              └─────────────┬─────────────┘
                            │
              ┌─────────────▼─────────────┐
              │ wlya relay                │
              │ (آینه WebSocket)          │
              └─────────────┬─────────────┘
                            │
              ┌─────────────▼─────────────┐      ┌─────┐
              │ Android قدیمی مادربزرگ   │ ──→  │ GSM │
              └───────────────────────────┘      └─────┘
```

**Control (agent)**

```
        agent ──→ ┌───────────┐ ←── یا خودت کنترل می‌کنی
                  │ phone-mcp │
                  └─────┬─────┘
                        │
              ┌─────────▼─────────┐
              │ imap, smtp, wlya —│
              │ هر کانال ارتباطی  │
              └─────────┬─────────┘
                        │
              ┌─────────▼─────────┐
              │ Android قدیمی     │
              │ مادربزرگ          │
              └───────────────────┘
```

جزئیات: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)، [`docs/PROTOCOL.md`](docs/PROTOCOL.md).

---

## شروع سریع

```bash
git clone https://github.com/tsol/bekon.git && cd bekon

npm run install:all
npm run demo
npm run relay:compose
npm run stack:start
npm run gateway:deploy
```

آدرس UI را از `npm run stack:status` بگیر (اغلب `http://127.0.0.1:5174`). آداپتورها و صدا را به **relay خودت** وصل کن.

**همه دستورات:** [`docs/COMMANDS.md`](docs/COMMANDS.md). **بر اساس سناریو:** [`docs/guides/GUIDES.md`](docs/guides/GUIDES.md).

---

## نقشه مخزن

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

## مستندات

| سند | محتوا |
|-----|--------|
| [`docs/USE-CASES.md`](docs/USE-CASES.md) | چهار سناریو |
| [`docs/guides/GUIDES.md`](docs/guides/GUIDES.md) | راهنمای گام‌به‌گام |
| [`docs/COMMANDS.md`](docs/COMMANDS.md) | اسکریپت‌های npm |

---

مجوز [AGPL-3.0-or-later](LICENSE). [`CONTRIBUTING.md`](CONTRIBUTING.md) و [`SECURITY.md`](SECURITY.md).

</div>
