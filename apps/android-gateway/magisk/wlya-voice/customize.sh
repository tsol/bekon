#!/system/bin/sh
# Magisk module. Priv-app permissions XML is in system/etc/permissions.
# CAPTURE_AUDIO_OUTPUT only applies if the APK is a privileged app
# (copy bekon into /system/priv-app or use Magisk overlay).
ui_print "- wlya-voice: audio concurrency props + privapp XML for pro.potoki.bekon"

APK="/data/local/tmp/bekon.apk"
if [ -f "$APK" ]; then
  ui_print "- found $APK, overlaying as priv-app"
  mkdir -p "$MODPATH/system/priv-app/Bekon"
  cp "$APK" "$MODPATH/system/priv-app/Bekon/Bekon.apk"
fi
