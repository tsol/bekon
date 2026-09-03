# Root kit (Line mode C)

Optional **root + call-audio** notes for Bekon Line mode C. Product behaviour: [`docs/LINE.md`](../docs/LINE.md). Magisk module lives in the tools tree, not under this folder.

Lab `DEVICE.md` files (serials, unlock tokens) are **gitignored** — do not link them from public docs. Brick / SPD trees such as `itel-a27/` stay local and are not part of the product surface.

## Layout

| Path | What |
|------|------|
| [`../tools/magisk/wlya-voice/`](../tools/magisk/wlya-voice/) | Magisk module (`CAPTURE_AUDIO_OUTPUT`, props) |
| [`vendor/`](vendor/) | Magisk APK (gitignored) |
| [`scripts/`](scripts/) | Shared ADB helpers |
| [`motorola/`](motorola/) | moto g(7) power — recon dumps |
| [`redmi/`](redmi/) | Redmi 9 — recon |
| [`oppo/`](oppo/) | OPPO A96 — recon |

## Commands

```bash
./root-phone/scripts/status.sh
./root-phone/scripts/recon.sh motorola
./root-phone/scripts/recon.sh oppo
./root-phone/scripts/recon.sh redmi
./root-phone/scripts/install-magisk-apk.sh
./root-phone/scripts/pack-module.sh          # → root-phone/out/wlya-voice.zip
```

After root, during a GSM call: `./root-phone/scripts/dump-mixer.sh redmi` (or `motorola` / `oppo`).

## Device folders

Public notes are the per-device `README.md` plus `recon/` (mixer, `getprop`, audio policy). Unlock wait times and bootloader quirks belong in those READMEs — not in committed serial files.
