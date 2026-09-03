#!/usr/bin/env bash
# Install Magisk Manager only. Does not patch boot / grant root.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=adb.sh
source "$ROOT/scripts/adb.sh"
VER="${MAGISK_VERSION:-v28.1}"
DIR="$ROOT/vendor"
mkdir -p "$DIR"
APK="$DIR/Magisk-${VER}.apk"
if [[ ! -f "$APK" ]]; then
  URL="https://github.com/topjohnwu/Magisk/releases/download/${VER}/Magisk-${VER}.apk"
  echo "fetch $URL"
  curl -fsSL -o "$APK" "$URL"
fi
#!/usr/bin/env bash
# Install Magisk Manager only. Does not patch boot / grant root.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=adb.sh
source "$ROOT/scripts/adb.sh"
VER="${MAGISK_VERSION:-v28.1}"
DIR="$ROOT/vendor"
mkdir -p "$DIR"
APK="$DIR/Magisk-${VER}.apk"
if [[ ! -f "$APK" ]]; then
  URL="https://github.com/topjohnwu/Magisk/releases/download/${VER}/Magisk-${VER}.apk"
  echo "fetch $URL"
  curl -fsSL -o "$APK" "$URL"
fi

if ! "$ADB" install -r "$APK"; then
  echo "Retrying after MIUI USB-install tweaks..."
  "$ADB" shell settings put global verifier_verify_adb_installs 0 2>/dev/null || true
  "$ADB" shell settings put global package_verifier_enable 0 2>/dev/null || true
  "$ADB" shell settings put secure adb_install_need_confirm 0 2>/dev/null || true
  if ! "$ADB" install -r "$APK"; then
    echo >&2
    echo "Install failed. On MIUI enable:" >&2
    echo "  Settings → Developer options → Install via USB (USB debugging security)" >&2
    echo "Or: adb push $APK /sdcard/Download/ and install from Files." >&2
    exit 1
  fi
fi
echo "Magisk app installed. Patch boot.img on the phone after unlock."
