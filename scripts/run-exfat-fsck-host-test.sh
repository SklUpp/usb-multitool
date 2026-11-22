#!/usr/bin/env bash
# Host-only exFAT fsck repair test. Runs on ubuntu-latest in CI.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="$ROOT/build/exfat-host"
SRC="$ROOT/app/src/main/cpp"
TP="$ROOT/third_party/exfatprogs"
mkdir -p "$BUILD"
${CC:-gcc} -std=gnu11 -O1 -g \
  -D_GNU_SOURCE -D_FILE_OFFSET_BITS=64 \
  -I"$SRC" -I"$TP/include" -I"$TP/fsck" \
  -Wno-unused-parameter -Wno-unused-function \
  -o "$BUILD/exfat_fsck_host_test" \
  "$SRC/host/exfat_fsck_host_test.c" \
  "$SRC/usb_block.c" \
  "$TP/lib/libexfat.c" \
  "$TP/lib/exfat_fs.c" \
  "$TP/lib/exfat_dir.c" \
  "$TP/lib/utils.c" \
  "$TP/fsck/fsck.c" \
  "$TP/fsck/repair.c"
"$BUILD/exfat_fsck_host_test" "$TP/testdata/exfat.img.tar.xz"
