#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/magisk/wlya-voice"
OUT="$ROOT/out"
mkdir -p "$OUT"
ZIP="$OUT/wlya-voice.zip"
rm -f "$ZIP"
( cd "$SRC" && zip -r "$ZIP" . -x '*.DS_Store' )
echo "module zip: $ZIP"
echo "Install in Magisk → Modules after su works."
