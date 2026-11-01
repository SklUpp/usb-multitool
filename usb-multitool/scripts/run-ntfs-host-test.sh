#!/usr/bin/env bash
# Host-only NTFS engine and dirty-flag clear test. Runs on ubuntu-latest in CI.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="$ROOT/build/ntfs-host"
SRC="$ROOT/app/src/main/cpp"
TP="$ROOT/third_party/ntfs-3g"
mkdir -p "$BUILD"

C_SOURCES=$(ls "$TP/libntfs-3g"/*.c | grep -v 'win32_io.c')

${CC:-gcc} -std=gnu11 -O2 -g   -D_GNU_SOURCE -D_FILE_OFFSET_BITS=64 -DHAVE_CONFIG_H=1   -I"$SRC"   -I"$TP/include"   -I"$TP/include/ntfs-3g"   -Wno-unused-parameter -Wno-unused-function -Wno-address-of-packed-member   -o "$BUILD/ntfs_host_test"   "$SRC/host/ntfs_host_test.c"   "$SRC/usb_block.c"   "$SRC/ntfs_engine.c"   $C_SOURCES

"$BUILD/ntfs_host_test" "$TP/testdata/ntfs.img.xz"
