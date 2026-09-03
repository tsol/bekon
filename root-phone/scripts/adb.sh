# shellcheck shell=bash
ADB="${ADB:-}"
if [[ -z "$ADB" ]]; then
  sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
  for c in "$sdk/platform-tools/adb" "$(command -v adb 2>/dev/null || true)"; do
    if [[ -n "$c" && -x "$c" ]]; then
      ADB="$c"
      break
    fi
  done
fi
if [[ -z "$ADB" || ! -x "$ADB" ]]; then
  echo "adb not found. Set ADB= or install platform-tools." >&2
  exit 1
fi
export ADB
