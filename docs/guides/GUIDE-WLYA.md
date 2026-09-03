# Guide: WLYA (backup transports)

**White List Your Ass** — keep a path to the home phone when the “real” internet is gone: parking garage, DPI, unplugged country. Stack **backup adapters** (email today; Lua/Telegram/MAX on the roadmap) so duty failover wakes a sleeping channel.

**Duty rules:** [`ARCHITECTURE.md`](../ARCHITECTURE.md#adapter-duty) · **Adapters:** [`packages/wlya-adapters/README.md`](../../packages/wlya-adapters/README.md) · **Commands:** [`COMMANDS.md`](../COMMANDS.md)

---

## What you need

| Piece | Role |
|-------|------|
| **Home phone** | Gateway APK with **wlyaserver** (primary) + **email** (or other backup) adapters. |
| **Laptop** | desktop-ui + wlya-tunnel — configure tunnels and adapter roles. |
| **Relay** | Usually `wlyaserver` HTTP inbox on your VPS. Email backup does not replace relay for voice — it is a **parallel transport** for tunnel messages. |

---

## 0. Clone and install

```bash
git clone https://github.com/tsol/bekon.git
cd bekon

npm run install:all
```

---

## 1. Relay (primary transport)

Deploy your own relay (recommended):

```bash
cp .env.deploy.example .env.deploy
# DEPLOY_HOST, DEPLOY_DIR, DEPLOY_HEALTH

npm run relay:deploy
curl -sS https://YOUR-RELAY.example/health
```

Local dev:

```bash
npm run relay:compose
```

Test-only shared host (RKN-blocked in Russia, no SLA): [`GUIDE-LINE.md`](GUIDE-LINE.md#c-shared-test-relay-wlyapotokipro).

---

## 2. Laptop stack

```bash
npm run stack:start
npm run stack:status
```

Open tunnels UI: `http://127.0.0.1:5174/#/tunnels`.

---

## 3. Phone — Gateway

```bash
npm run gateway:deploy
```

Enable **Accept advertised adapters** on the phone if you push config from desktop-ui.

---

## 4. desktop-ui — tunnel with primary + backup

1. **Create tunnel** — strong **Channel** + **Secret** (same on phone).
2. Add **wlyaserver** adapter:
   - Relay URL → `https://YOUR-RELAY.example`
   - Role: **primary**
   - Normal poll interval (e.g. a few seconds).
3. Add **email** adapter (backup):
   - Role: **backup**
   - IMAP/SMTP credentials for a mailbox both sides can reach.
   - Duty: sleeps ~hourly until foreign inbound or primary failure — see [`ARCHITECTURE.md`](../ARCHITECTURE.md#adapter-duty).
4. **Start** tunnel on desktop-ui; **Start All** on phone (or advertise adapters from UI).

**Typical story:** relay blocked → you send from desktop via email → within about an hour the phone wakes the email adapter → that channel runs at full speed while packets keep arriving.

---

## 5. Verify failover (dev)

Use the **mock** adapter in desktop-ui for local tests without a phone (`apps/desktop-ui` tunnel adapters). For real email backup, send a test message on the backup path and watch duty badges / logs in the Tunnels UI.

Adapter codegen and adding new types: [`packages/wlya-adapters/README.md`](../../packages/wlya-adapters/README.md).

---

## 6. Mock adapter (no phone)

```bash
npm run stack:start
```

In `/#/tunnels`, add the **mock** adapter to exercise duty coordinator logic without Android hardware.

---

## Roadmap (not in this guide yet)

- Lua script adapters (Telegram, Sheets, stego-email, MAX) without a new APK.
- See [`USE-CASES.md`](../USE-CASES.md#4-white-list-your-ass--adapters).

---

## Stop / reset

```bash
npm run stack:stop
```

---

## Troubleshooting

| Symptom | Check |
|---------|--------|
| Only relay works | Email credentials; backup **role**; phone polling logs in tunnel log pane. |
| Backup never wakes | No foreign inbound on backup; primary still healthy; `idleMs` / sleep settings in adapter form. |
| Confused with voice | Tunnel adapters ≠ voice room — [`GUIDE-LINE.md`](GUIDE-LINE.md#5-match-tunnel--voice-checklist). |

---

## Related guides

| Guide | Use case |
|-------|----------|
| [`GUIDE-LINE.md`](GUIDE-LINE.md) | Voice + GSM on the same relay |
| [`GUIDE-CONTROL.md`](GUIDE-CONTROL.md) | Control queue over the tunnel |
| [`GUIDE-SPEAKER.md`](GUIDE-SPEAKER.md) | Walkie without backup transports |
