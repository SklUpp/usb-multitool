#!/usr/bin/env bash
# Builds the SectorForge sf-core rescue bridge (Rust static library) the app links
# into libusb_native.so. Gradle runs the same cargo invocation as buildSfBridge;
# this script exists for manual/CI use and matches its output path.
#
# Requires the pinned sources (`scripts/fetch-third-party.sh`) and a Rust target:
#   rustup target add aarch64-linux-android
set -euo pipefail

ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
TARGET="aarch64-linux-android"
RUST_TOOLCHAIN="1.99.1"
OUT="$ROOT/app/build/rust"

CARGO_TARGET_DIR="$OUT" cargo "+$RUST_TOOLCHAIN" build --locked --release --target "$TARGET" \
  --manifest-path "$ROOT/app/src/main/rust/sf-bridge/Cargo.toml"

LIB="$OUT/$TARGET/release/libsf_bridge.a"
[[ -f "$LIB" ]] || { echo "SectorForge bridge not produced at $LIB" >&2; exit 1; }
echo "$LIB"
