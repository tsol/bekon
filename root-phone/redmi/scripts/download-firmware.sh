#!/usr/bin/env bash
# Download Redmi 9 galahad_ru V12.0.3.0.QJCRUXM and extract boot.img
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FW="$ROOT/firmware"
OUT="$ROOT/out"
mkdir -p "$FW" "$OUT"

# galahad_ru uses mod_device lancelot_ru_global; same MIUI build V12.0.3.0.QJCRUXM
FASTBOOT_TGZ="lancelot_ru_global_images_V12.0.3.0.QJCRUXM_20210319.0000.00_10.0_global_0e46b1c708.tgz"
RECOVERY_ZIP="miui_LANCELOTRUGlobal_V12.0.3.0.QJCRUXM_de8b84a9c4_10.0.zip"
MD5_FASTBOOT="0e46b1c708ab12d35716da344c441cfa"

usage() {
  cat <<EOF
Usage: $0 [fastboot|recovery|boot-only]

  fastboot   Download full fastboot .tgz (~3.9 GB), extract boot.img
  recovery   Download recovery .zip (~2.1 GB), extract boot.img if present
  boot-only  Only extract boot.img if archive already in firmware/

Files land in: $FW/
boot.img -> $FW/boot.img and $OUT/stock_boot.img
EOF
}

mode="${1:-fastboot}"

extract_boot_from_dir() {
  local dir="$1"
  local boot
  boot="$(find "$dir" -name boot.img -type f | head -1)"
  if [[ -z "$boot" ]]; then
    echo "boot.img not found under $dir" >&2
    return 1
  fi
  cp -f "$boot" "$FW/boot.img"
  cp -f "$boot" "$OUT/stock_boot.img"
  echo "boot.img: $FW/boot.img ($(du -h "$FW/boot.img" | cut -f1))"
  echo "copy:     $OUT/stock_boot.img"
}

case "$mode" in
  boot-only)
    tgz="$FW/$FASTBOOT_TGZ"
    if [[ ! -f "$tgz" ]]; then
      echo "Missing $tgz — run: $0 fastboot" >&2
      exit 1
    fi
    tmp="$(mktemp -d)"
    trap 'rm -rf "$tmp"' EXIT
    tar -xzf "$tgz" -C "$tmp"
    extract_boot_from_dir "$tmp"
    ;;
  fastboot)
    tgz="$FW/$FASTBOOT_TGZ"
    if [[ ! -f "$tgz" ]]; then
      echo "Downloading fastboot ROM (~3.7 GB) from bigota.d.miui.com ..."
      curl -fL --retry 5 --continue-at - \
        -o "$tgz.part" \
        "https://bigota.d.miui.com/V12.0.3.0.QJCRUXM/lancelot_ru_global_images_V12.0.3.0.QJCRUXM_20210319.0000.00_10.0_global_0e46b1c708.tgz"
      mv "$tgz.part" "$tgz"
    else
      echo "Already have $tgz"
    fi
    if command -v md5sum >/dev/null; then
      got="$(md5sum "$tgz" | awk '{print $1}')"
      if [[ "$got" != "$MD5_FASTBOOT" ]]; then
        echo "WARN: MD5 mismatch (got $got, want $MD5_FASTBOOT)" >&2
      else
        echo "MD5 OK"
      fi
    fi
    tmp="$(mktemp -d)"
    trap 'rm -rf "$tmp"' EXIT
    echo "Extracting..."
    tar -xzf "$tgz" -C "$tmp"
    extract_boot_from_dir "$tmp"
    ;;
  recovery)
    zip="$FW/$RECOVERY_ZIP"
    if [[ ! -f "$zip" ]]; then
      echo "Downloading recovery ROM (~2.0 GB) from bigota.d.miui.com ..."
      curl -fL --retry 5 --continue-at - \
        -o "$zip.part" \
        "https://bigota.d.miui.com/V12.0.3.0.QJCRUXM/miui_LANCELOTRUGlobal_V12.0.3.0.QJCRUXM_de8b84a9c4_10.0.zip"
      mv "$zip.part" "$zip"
    fi
    tmp="$(mktemp -d)"
    trap 'rm -rf "$tmp"' EXIT
    unzip -q "$zip" -d "$tmp"
    if [[ -f "$tmp/boot.img" ]]; then
      cp -f "$tmp/boot.img" "$FW/boot.img"
      cp -f "$tmp/boot.img" "$OUT/stock_boot.img"
      echo "boot.img from recovery zip"
    else
      echo "No boot.img in recovery zip; use fastboot mode" >&2
      exit 1
    fi
    ;;
  *)
    usage
    exit 1
    ;;
esac
