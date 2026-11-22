#!/usr/bin/env bash
# Host-only FAT streaming regression. Runs on ubuntu-latest in CI.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="$ROOT/build/fat-host"
SRC="$ROOT/app/src/main/cpp"
FATFS="$ROOT/third_party/fatfs/source"
mkdir -p "$BUILD"
${CC:-gcc} -std=gnu11 -O2 -g \
  -D_GNU_SOURCE -D_FILE_OFFSET_BITS=64 \
  -pthread \
  -Wall -Wextra -Wno-unused-parameter -Wno-unused-function -Wno-null-character \
  -I"$SRC" -I"$FATFS" \
  -o "$BUILD/fat_stream_host_test" \
  "$SRC/host/fat_stream_host_test.c" \
  "$SRC/fat_engine.c" \
  "$SRC/usb_block.c" \
  "$FATFS/ff.c" \
  "$FATFS/ffsystem.c" \
  "$FATFS/ffunicode.c" \
  "$FATFS/diskio.c"
"$BUILD/fat_stream_host_test"
