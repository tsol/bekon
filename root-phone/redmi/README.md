# Redmi 9 — M2004J19C (`galahad_ru`)

MediaTek MT6768, MIUI V12. Lab notes for root + Line mode C.

| | |
|---|---|
| Recon | [recon/](recon/) |
| Firmware (local) | `firmware/` (gitignored) — drop stock `boot.img` here |
| Build output | `out/` (gitignored) |

## Outline

1. Modes A/B without root (walkie / acoustic).
2. OEM unlock + Mi Account + **168 hour** wait.
3. Patch stock `boot.img` with Magisk → Mi Unlock → `fastboot flash boot`.
4. Flash [`../../apps/android-gateway/magisk/wlya-voice/`](../../apps/android-gateway/magisk/wlya-voice/) via `../scripts/pack-module.sh`.

```bash
./root-phone/scripts/status.sh
./root-phone/scripts/recon.sh redmi
./root-phone/scripts/install-magisk-apk.sh
```

Standard Xiaomi path — no SPD dongle. Brick risk is much lower than Unisoc/itel recovery.
