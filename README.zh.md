<div align="center">

<img src="docs/bekon-icon.png" alt="Bekon" width="128">

# Bekon Suite · Be Konnected

**WLYA 在线路上。Bekon 在房间里。**

[English](README.md) · [Русский](README.ru.md) · [Українська](README.uk.md) · [Беларуская](README.be.md) · **中文** · [فارسی](README.fa.md)

</div>

自建 relay，你拥有自己的 endpoint。文档中的示例主机名仅供参考。

---

## 使用场景

为什么做这件事。更多细节与路线图：[`docs/USE-CASES.md`](docs/USE-CASES.md)（英文）。

### 1. 移民网关

离开祖国时，把一台**已 root、插着本地 SIM 的 Android** 留给奶奶（或任何可信的人）。通过那部手机**拨打和接听**——征兵办、内政部、监狱系统、银行，只认国内号码的机构，you name it。

**指南：** [`docs/guides/GUIDE-LINE.md`](docs/guides/GUIDE-LINE.md)

### 2. 给你的 agent 一部手机

接上 MCP，让 agent **真正拥有一台 Android**：点击、滑动、打开应用、用你的卡付款、在 Instagram 上 doomscroll、在设置里磨蹭到搞定为止。

**指南：** [`docs/guides/GUIDE-CONTROL.md`](docs/guides/GUIDE-CONTROL.md)

### 3. 旧 Android 变智能音箱

解雇 Alice 和 Alexa——通过桌上落灰的手机直接和 **Hermes** 对话。

**指南：** [`docs/guides/GUIDE-SPEAKER.md`](docs/guides/GUIDE-SPEAKER.md)

### 4. White List Your Ass

**WLYA** = White List Your Ass。通过 **邮件**、**表格**、**MAX** 或任意自定义隧道适配器控制手机——包括在停车场、所谓「正常」网络已经没了的时候。堆尽可能多的备用适配器，别断了回家的路。White List Your Ass!

**指南：** [`docs/guides/GUIDE-WLYA.md`](docs/guides/GUIDE-WLYA.md)

---

## 产品

| 层 | 名称 | 作用 |
|----|------|------|
| 传输 | **WLYA Tunnel** | HMAC 通道、适配器、duty 故障转移。**White List Your Ass.** 协议 `seed`；UI **Secret**。 |
| 远程 UI | **Bekon Control** | 屏幕、手势、文件、MCP。完整 Gateway APK（`pro.potoki.bekon`）。 |
| GSM / 语音 | **Bekon Line** | 国内 SIM 作锚点。客户端：**Bekon Phone**。 |
| 整体 | **Bekon Suite** | 线路 + 房间里的设备。口号：Be Konnected。 |

**Line（语音）**

```
              ┌───────────────────────────┐
              │ 口袋里的手机，              │
              │ 人在格鲁吉亚                │
              └─────────────┬─────────────┘
                            │
              ┌─────────────▼─────────────┐
              │ wlya relay                │
              │（WebSocket 镜像）         │
              └─────────────┬─────────────┘
                            │
              ┌─────────────▼─────────────┐      ┌─────┐
              │ 奶奶的旧 Android          │ ──→  │ GSM │
              └───────────────────────────┘      └─────┘
```

**Control（agent）**

```
        agent ──→ ┌───────────┐ ←── 或你自己操控
                  │ phone-mcp │
                  └─────┬─────┘
                        │
              ┌─────────▼─────────┐
              │ imap, smtp, wlya —│
              │ 任意通道            │
              └─────────┬─────────┘
                        │
              ┌─────────▼─────────┐
              │ 奶奶的旧 Android  │
              └───────────────────┘
```

协议细节：[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)、[`docs/PROTOCOL.md`](docs/PROTOCOL.md)。

---

## 快速开始

```bash
git clone https://github.com/tsol/bekon.git && cd bekon

npm run install:all
npm run demo
npm run relay:compose
npm run stack:start
npm run gateway:deploy
```

用 `npm run stack:status` 查看 UI 地址（常为 `http://127.0.0.1:5174`）。适配器与语音指向**你自己的** relay。

**全部命令：** [`docs/COMMANDS.md`](docs/COMMANDS.md)。**分场景指南：** [`docs/guides/GUIDES.md`](docs/guides/GUIDES.md)。

---

## 仓库结构

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

## 文档

| 文档 | 内容 |
|------|------|
| [`docs/USE-CASES.md`](docs/USE-CASES.md) | 四个场景与路线图 |
| [`docs/guides/GUIDES.md`](docs/guides/GUIDES.md) | 分步指南 |
| [`docs/COMMANDS.md`](docs/COMMANDS.md) | npm 脚本 |

---

许可协议 [AGPL-3.0-or-later](LICENSE)。见 [CONTRIBUTING.md](CONTRIBUTING.md) 与 [SECURITY.md](SECURITY.md)。
