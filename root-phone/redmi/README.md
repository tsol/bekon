# Redmi 9 — M2004J19C (galahad_ru)

MediaTek MT6768, MIUI V12.0.3.0.QJCRUXM. **Активный** телефон для root + voice mode C.

| | |
|---|---|
| Профиль | [DEVICE.md](DEVICE.md) |
| Recon | [recon/](recon/) |
| Firmware | [firmware/](firmware/) (stock boot.img сюда) |
| Build output | [out/](out/) |

## План

1. Режим A/B без root (walkie / acoustic probe).
2. OEM unlock + Mi Account + ожидание 168 ч.
3. Stock `boot.img` → Magisk patch → Mi Unlock → `fastboot flash boot`.
4. [../magisk/wlya-voice/](../magisk/wlya-voice/) через `../scripts/pack-module.sh`.

## Команды

```bash
./root-phone/scripts/status.sh
./root-phone/scripts/recon.sh redmi
./root-phone/scripts/install-magisk-apk.sh
```

Стандартный Xiaomi-путь — **без SPD**, риск brick намного ниже itel.
