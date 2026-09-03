# wlya-voice Magisk module

- `system.prop` — voice concurrency flags (gsm2sip).
- `privapp-permissions-wlya.xml` — `CAPTURE_AUDIO_OUTPUT` + `WRITE_SECURE_SETTINGS` for `pro.potoki.bekon`.
- `service.sh` — after reboot: grants, a11y, Bekon Keys, start `AgentForegroundService` (tunnel watchdog).
- Optional: `adb push app-debug.apk /data/local/tmp/bekon.apk` before flashing the zip so `customize.sh` overlays it as `/system/priv-app/Bekon`.

`./apps/android-gateway/scripts/deploy deploy --magisk` copies the APK into the module overlay and reboots (priv-app). `--no-reboot` skips reboot. Unrooted: `./apps/android-gateway/scripts/deploy deploy` is a plain `pm install`.

Pack: from repo root, `./root-phone/scripts/pack-module.sh` zips this directory to `root-phone/out/wlya-voice.zip`.
