#!/usr/bin/env bash
set -euo pipefail
# Read only hardware facts. Never prints device serial, contacts, messages or other app data.
adb -d get-state >/dev/null
for property in ro.product.manufacturer ro.product.model ro.build.version.release ro.build.version.sdk ro.product.cpu.abi ro.soc.model; do
  printf '%s: ' "$property"
  adb -d shell getprop "$property"
done
adb -d shell cat /proc/meminfo | head -5
adb -d shell df -h /data
printf 'Airplane-mode flag: '
adb -d shell settings get global airplane_mode_on
