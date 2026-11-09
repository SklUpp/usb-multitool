#!/usr/bin/env bash
# Host-only EXT engine test against pinned lwext4. Runs on ubuntu-latest in CI.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="$ROOT/build/ext-host"
SRC="$ROOT/app/src/main/cpp"
TP="$ROOT/third_party/lwext4"

if [[ ! -f "$TP/include/ext4.h" ]]; then
  echo "lwext4 missing; fetching pin via scripts/fetch-third-party.sh"
  "$ROOT/scripts/fetch-third-party.sh"
fi

mkdir -p "$BUILD"
mapfile -t LWEXT4_SRC < <(ls "$TP/src"/*.c)

${CC:-gcc} -std=gnu11 -O2 -g \
  -D_GNU_SOURCE -D_FILE_OFFSET_BITS=64 \
  -DCONFIG_USE_DEFAULT_CFG=1 \
  -DCONFIG_DEBUG_ASSERT=0 \
  -DCONFIG_EXT4_MOUNTPOINTS_COUNT=8 \
  -I"$SRC" \
  -I"$TP/include" \
  -Wno-unused-parameter -Wno-unused-function \
  -o "$BUILD/ext_host_test" \
  "$SRC/host/ext_host_test.c" \
  "$SRC/usb_block.c" \
  "$SRC/ext_engine.c" \
  "${LWEXT4_SRC[@]}" \
  -lpthread

(cd "$BUILD" && ./ext_host_test)
