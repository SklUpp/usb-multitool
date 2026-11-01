#!/usr/bin/env sh
# Host JVM check for the GPT critical-sector restore path (no Gradle / no phone).
set -eu
ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
OUT="${TMPDIR:-${PREFIX:-/tmp}/tmp}/usb-gpt-backup-$$"
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
  "$ROOT/app/src/main/kotlin/usb/device/BlockDevice.kt" \
  "$ROOT/app/src/main/kotlin/usb/multitool/GptCriticalBackup.kt" \
  "$ROOT/app/src/test/kotlin/usb/ui/GptCriticalBackupHostCheck.kt"

kotlin -cp "$OUT:$CP" usb.ui.GptCriticalBackupHostCheck
