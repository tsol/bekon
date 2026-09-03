#!/usr/bin/env bash
set -u

# shellcheck source=../../scripts/adb.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/scripts/adb.sh"

# OPPO A96 CPH2333 — READ-ONLY root/unlock reconnaissance
# IMPORTANT: this script NEVER unlocks, flashes, erases, or modifies the phone.
# It only queries ADB/Fastboot state and saves the results.

OUT="${1:-oppo-a96-recon-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$OUT"

LOG="$OUT/report.txt"
exec > >(tee "$LOG") 2>&1

section() {
  echo
  echo "============================================================"
  echo "$1"
  echo "============================================================"
}

run() {
  echo
  echo "+ $*"
  "$@" 2>&1 || echo "[exit=$?]"
}

adb_shell() {
  echo
  echo "+ adb shell $*"
  adb shell "$@" 2>&1 || echo "[exit=$?]"
}

echo "OPPO A96 CPH2333 READ-ONLY RECON"
echo "Output: $(pwd)/$OUT"
echo "Date: $(date)"
echo
echo "NO unlock / flash / erase commands are used."

section "1. HOST TOOLS"

run adb version
run fastboot --version
run lsusb

section "2. ADB DEVICE DETECTION"

run adb start-server
run adb devices -l

if adb get-state >/dev/null 2>&1; then
  echo "ADB device detected."

  section "3. BASIC DEVICE IDENTITY"

  adb_shell getprop ro.product.manufacturer
  adb_shell getprop ro.product.brand
  adb_shell getprop ro.product.name
  adb_shell getprop ro.product.device
  adb_shell getprop ro.product.model
  adb_shell getprop ro.product.marketname
  adb_shell getprop ro.build.version.release
  adb_shell getprop ro.build.version.sdk
  adb_shell getprop ro.build.display.id
  adb_shell getprop ro.build.version.incremental
  adb_shell getprop ro.build.version.security_patch
  adb_shell getprop ro.boot.hardware
  adb_shell getprop ro.soc.manufacturer
  adb_shell getprop ro.soc.model

  section "4. BOOT / AVB / OEM UNLOCK STATE"

  adb_shell getprop ro.boot.flash.locked
  adb_shell getprop ro.boot.verifiedbootstate
  adb_shell getprop ro.boot.vbmeta.device_state
  adb_shell getprop ro.boot.slot_suffix
  adb_shell getprop ro.boot.slot
  adb_shell getprop ro.boot.dynamic_partitions
  adb_shell getprop ro.boot.boot_devices
  adb_shell getprop sys.oem_unlock_allowed
  adb_shell getprop ro.oem_unlock_supported
  adb_shell getprop ro.boot.veritymode
  adb_shell getprop ro.boot.avb_version
  adb_shell getprop ro.boot.warranty_bit

  section "5. CPU / STORAGE"

  adb_shell cat /proc/cpuinfo
  adb_shell cat /proc/cmdline
  adb_shell cat /proc/partitions
  adb_shell df -h

  section "6. BLOCK DEVICE MAP"

  adb_shell ls -la /dev/block/by-name
  adb_shell ls -la /dev/block/bootdevice/by-name
  adb_shell ls -la /dev/block/platform/*/by-name

  section "7. IMPORTANT PARTITION LINKS"

  for p in boot init_boot vendor_boot vbmeta vbmeta_a vbmeta_b dtbo dtbo_a dtbo_b super userdata misc metadata modem modemst1 modemst2 persist recovery; do
    echo
    echo "--- $p ---"
    adb_shell ls -l "/dev/block/by-name/$p"
    adb_shell ls -l "/dev/block/bootdevice/by-name/$p"
  done

  section "8. MOUNT / SECURITY"

  adb_shell id
  adb_shell getenforce
  adb_shell mount
  adb_shell cat /proc/mounts

  section "9. FASTBOOT TRANSITION"

  echo
  echo "Rebooting to bootloader/fastboot. This is non-destructive."
  run adb reboot bootloader

  echo
  echo "Waiting up to 20 seconds for fastboot..."
  for i in $(seq 1 20); do
    if fastboot devices 2>/dev/null | grep -q .; then
      echo "Fastboot device detected."
      break
    fi
    sleep 1
  done
else
  echo "No usable ADB device detected."
  echo "If the phone is already in fastboot, the next section may still work."
fi

section "10. FASTBOOT DEVICE"

run fastboot devices

if fastboot devices 2>/dev/null | grep -q .; then

  section "11. FASTBOOT READ-ONLY VARIABLES"

  run fastboot getvar product
  run fastboot getvar product_name
  run fastboot getvar unlocked
  run fastboot getvar secure
  run fastboot getvar current-slot
  run fastboot getvar slot-count
  run fastboot getvar slot-successful:a
  run fastboot getvar slot-successful:b
  run fastboot getvar slot-unbootable:a
  run fastboot getvar slot-unbootable:b
  run fastboot getvar is-userspace
  run fastboot getvar is-userspace
  run fastboot flashing get_unlock_ability

  section "12. FASTBOOT GETVAR ALL"

  echo "Saving full getvar output..."
  fastboot getvar all > "$OUT/fastboot-getvar-all.txt" 2>&1 || true
  cat "$OUT/fastboot-getvar-all.txt"

  section "13. FASTBOOT CAPABILITIES"

  run fastboot getvar has-slot:boot
  run fastboot getvar has-slot:init_boot
  run fastboot getvar has-slot:vendor_boot
  run fastboot getvar has-slot:vbmeta
  run fastboot getvar has-slot:super
  run fastboot getvar partition-type:boot
  run fastboot getvar partition-size:boot
  run fastboot getvar partition-type:init_boot
  run fastboot getvar partition-size:init_boot
  run fastboot getvar partition-type:vendor_boot
  run fastboot getvar partition-size:vendor_boot
  run fastboot getvar partition-type:vbmeta
  run fastboot getvar partition-size:vbmeta

  section "14. FASTBOOT COMMAND AVAILABILITY"

  echo "Running 'fastboot help' only; no modifying command is executed."
  run fastboot help

  section "15. USB ID IN FASTBOOT"

  run lsusb
else
  echo "No fastboot device detected."
fi

section "16. SUMMARY / NEXT STEP"

echo "Recon complete."
echo
echo "Report: $LOG"
echo "Full fastboot variables: $OUT/fastboot-getvar-all.txt"
echo
echo "IMPORTANT:"
echo "This script did NOT run:"
echo "  fastboot flashing unlock"
echo "  fastboot oem unlock"
echo "  fastboot erase"
echo "  fastboot format"
echo "  fastboot flash"
echo "  fastboot boot"
echo
echo "Send the entire $OUT directory (or at least report.txt and fastboot-getvar-all.txt)"
echo "for analysis before attempting any unlock or flashing operation."
