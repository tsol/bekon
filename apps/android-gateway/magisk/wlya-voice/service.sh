#!/system/bin/sh
# late_start: grants + a11y + Bekon Keys so the app is ready after reboot.
MODDIR="${0%/*}"
PKG="pro.potoki.bekon"
A11Y="$PKG/pro.potoki.bekon.touch.TouchService"
IME="$PKG/.ime.BekonImeService"
NL="$PKG/pro.potoki.bekon.sms.SmsListener"

append_secure() {
  key="$1"
  val="$2"
  cur="$(settings get secure "$key" 2>/dev/null || true)"
  [ "$cur" = "null" ] && cur=""
  case ":$cur:" in
    *":$val:"*) return 0 ;;
  esac
  if [ -z "$cur" ]; then
    settings put secure "$key" "$val"
  else
    settings put secure "$key" "$cur:$val"
  fi
}

until [ "$(getprop sys.boot_completed)" = "1" ]; do
  sleep 2
done

i=0
while [ "$i" -lt 30 ]; do
  pm path "$PKG" >/dev/null 2>&1 && break
  i=$((i + 1))
  sleep 2
done

pm grant "$PKG" android.permission.RECORD_AUDIO 2>/dev/null
pm grant "$PKG" android.permission.READ_PHONE_STATE 2>/dev/null
pm grant "$PKG" android.permission.READ_SMS 2>/dev/null
pm grant "$PKG" android.permission.RECEIVE_SMS 2>/dev/null
pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null

append_secure enabled_accessibility_services "$A11Y"
settings put secure accessibility_enabled 1
append_secure enabled_notification_listeners "$NL"

ime enable "$IME" 2>/dev/null
ime set "$IME" 2>/dev/null

dumpsys deviceidle whitelist +"$PKG" >/dev/null 2>&1
cmd appops set "$PKG" RUN_IN_BACKGROUND allow 2>/dev/null
cmd appops set "$PKG" RUN_ANY_IN_BACKGROUND allow 2>/dev/null

# Start All equivalent: FGS + WLYA watchdog (PREF_RUNNING / rooted auto-resume).
am start-foreground-service --user 0 -n "$PKG/.AgentForegroundService" 2>/dev/null \
  || am startservice --user 0 -n "$PKG/.AgentForegroundService" 2>/dev/null \
  || true

