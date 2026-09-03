#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEVICE="${1:-oppo}"
OUT="$ROOT/$DEVICE/recon"
# shellcheck source=adb.sh
source "$ROOT/scripts/adb.sh"
mkdir -p "$OUT"

"$ADB" devices -l | tee "$OUT/devices.txt"
"$ADB" shell getprop > "$OUT/getprop.txt"
"$ADB" shell cat /proc/cpuinfo > "$OUT/cpuinfo.txt"
"$ADB" shell ls -l /dev/block/by-name > "$OUT/by-name.txt"
"$ADB" shell ls -l /dev/snd > "$OUT/snd.txt" || true
"$ADB" shell dumpsys audio > "$OUT/dumpsys-audio.txt"
"$ADB" shell dumpsys media.audio_policy > "$OUT/dumpsys-audio-policy.txt" || true
"$ADB" pull /vendor/etc/audio_policy_configuration.xml "$OUT/audio_policy_configuration.xml" || true
"$ADB" pull /vendor/etc/primary_audio_policy_configuration.xml "$OUT/primary_audio_policy_configuration.xml" || true
"$ADB" pull /vendor/etc/audio_hw.xml "$OUT/audio_hw.xml" || true
echo "wrote $OUT"
