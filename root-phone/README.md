# Root + GSM line (wlya voice)

Общий kit для режима **C** ([`docs/line.md`](../docs/line.md)): root, call-audio, Magisk-модуль.

## Структура

| Путь | Назначение |
|---|---|
| [magisk/wlya-voice/](magisk/wlya-voice/) | Magisk-модуль (props + `CAPTURE_AUDIO_OUTPUT`) — **общий** |
| [vendor/](vendor/) | Magisk APK (gitignore) |
| [scripts/](scripts/) | Общие ADB-скрипты |
| [itel-a27/](itel-a27/) | itel A551L-Pro — **brick / SPD recovery** (приостановлен) |
| [redmi/](redmi/) | Redmi 9 — Mi Unlock (168 ч), подготовка Magisk |
| [oppo/](oppo/) | OPPO A96 CPH2333 — ADB, TR/EUEX, BL unlock часто нет |
| [motorola/](motorola/) | **moto g(7) power XT1955-7** — активный ADB, RETRU, OEM unlock=1 |

## Общие команды

```bash
./root-phone/scripts/status.sh
./root-phone/scripts/recon.sh motorola
./root-phone/scripts/recon.sh oppo
./root-phone/scripts/recon.sh redmi
./root-phone/scripts/install-magisk-apk.sh
./root-phone/scripts/pack-module.sh          # → out/wlya-voice.zip
```

После root: `./root-phone/scripts/dump-mixer.sh redmi` (во время GSM-звонка).

## Активный телефон

**moto g(7) power XT1955-7** (`ocean`, RETRU) — [motorola/DEVICE.md](motorola/DEVICE.md). ADB OK, `sys.oem_unlock_allowed=1`, официальный Motorola unlock (без 168 ч). Лучший кандидат на root.

**Redmi 9** — [redmi/DEVICE.md](redmi/DEVICE.md). Ждёт Mi Unlock 168 ч.

**OPPO A96 CPH2333** (TR) — [oppo/DEVICE.md](oppo/DEVICE.md). ADB OK, но официальный BL unlock для EUEX часто недоступен.

## Приостановлен

**itel A551L** — bootloop, BROM recovery. Lab notes only; not part of the public Bekon product. Handoff: [itel-a27/AGENT-HANDOFF.md](itel-a27/AGENT-HANDOFF.md).
