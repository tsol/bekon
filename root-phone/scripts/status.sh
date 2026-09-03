#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=adb.sh
source "$ROOT/scripts/adb.sh"

"$ADB" devices -l
echo
echo "=== identity ==="
for k in ro.product.model ro.product.device ro.product.name ro.build.display.id \
  ro.board.platform ro.boot.slot_suffix ro.boot.flash.locked sys.oem_unlock_allowed \
  ro.oem_unlock_supported; do
  printf '%s=%s\n' "$k" "$("$ADB" shell getprop "$k" | tr -d '\r')"
done
echo
echo "=== settings ==="
echo -n "oem_unlock_allowed="; "$ADB" shell settings get global oem_unlock_allowed | tr -d '\r'
echo
echo "=== shell id ==="
"$ADB" shell id | tr -d '\r'
echo
echo "=== su / magisk ==="
"$ADB" shell 'command -v su; command -v magisk; su -c id 2>/dev/null; magisk -v 2>/dev/null' | tr -d '\r' || true
echo
echo "=== tinymix ==="
"$ADB" shell 'tinymix >/dev/null 2>&1 && echo tinymix_ok || echo tinymix_fail' | tr -d '\r'
