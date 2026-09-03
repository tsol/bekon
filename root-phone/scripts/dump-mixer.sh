#!/usr/bin/env bash
# After root, run during OFFHOOK. tinymix needs audio/mixer access.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEVICE="${1:-oppo}"
# shellcheck source=adb.sh
source "$ROOT/scripts/adb.sh"
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="$ROOT/$DEVICE/recon/mixer-$STAMP.txt"
{
  echo "=== date $STAMP ==="
  "$ADB" shell 'su -c id'
  echo "=== tinymix ==="
  "$ADB" shell 'su -c tinymix' || true
  echo "=== dumpsys audio (mode) ==="
  "$ADB" shell dumpsys audio | grep -E 'mMode|MODE_|In-call|call state' || true
} | tee "$OUT"
echo "wrote $OUT"
