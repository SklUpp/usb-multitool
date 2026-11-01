#!/usr/bin/env sh
# Host JVM check for pure UI state (no Gradle / no phone).
set -eu
ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
OUT="${TMPDIR:-${PREFIX:-/tmp}/tmp}/usb-ui-host-$$"
mkdir -p "$OUT"
cleanup() { rm -rf "$OUT"; }
trap cleanup EXIT

STDLIB="/data/data/com.termux/files/usr/opt/kotlin/lib/kotlin-stdlib.jar"
JDK7="/data/data/com.termux/files/usr/opt/kotlin/lib/kotlin-stdlib-jdk7.jar"
JDK8="/data/data/com.termux/files/usr/opt/kotlin/lib/kotlin-stdlib-jdk8.jar"
CP="$STDLIB:$JDK7:$JDK8"

kotlinc \
  -cp "$CP" \
  -d "$OUT" \
  "$ROOT/app/src/main/kotlin/usb/ui/UiModels.kt" \
  "$ROOT/app/src/main/kotlin/usb/ui/UiReducer.kt" \
  "$ROOT/app/src/test/kotlin/usb/ui/UiStateHostCheck.kt"

kotlin -cp "$OUT:$CP" usb.ui.UiStateHostCheck
