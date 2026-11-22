#!/usr/bin/env bash
# Host-only usb_block registry test. Runs on ubuntu-latest in CI.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="$ROOT/build/usb-block-host"
SRC="$ROOT/app/src/main/cpp"
mkdir -p "$BUILD"
${CC:-gcc} -std=gnu11 -O1 -g \
  -pthread \
  -Wall -Wextra \
  -I"$SRC" \
  -o "$BUILD/usb_block_host_test" \
  "$SRC/host/usb_block_host_test.c" \
  "$SRC/usb_block.c"
"$BUILD/usb_block_host_test"
