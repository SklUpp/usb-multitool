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

# FilesystemSpace/FilesystemUsage come from the measurement backend; include them only once
# that file exists, so this check keeps compiling while the backend lands separately.
set -- \
  "$ROOT/app/src/main/kotlin/usb/FormatBytes.kt" \
  "$ROOT/app/src/main/kotlin/usb/diagnostics/ScanReport.kt" \
  "$ROOT/app/src/main/kotlin/usb/diagnostics/RescueMap.kt" \
  "$ROOT/app/src/main/kotlin/usb/diagnostics/BackupArchive.kt" \
  "$ROOT/app/src/main/kotlin/usb/fs/FilesystemId.kt" \
  "$ROOT/app/src/main/kotlin/usb/fs/FormatCatalog.kt" \
  "$ROOT/app/src/main/kotlin/usb/partition/PartitionModels.kt" \
  "$ROOT/app/src/main/kotlin/usb/partition/PartitionClassification.kt" \
  "$ROOT/app/src/main/kotlin/usb/device/BlockDevice.kt" \
  "$ROOT/app/src/main/kotlin/usb/partition/MbrTable.kt" \
  "$ROOT/app/src/main/kotlin/usb/partition/GptTable.kt" \
  "$ROOT/app/src/main/kotlin/usb/partition/PartitionTable.kt" \
  "$ROOT/app/src/main/kotlin/usb/partition/RawRangePlan.kt" \
  "$ROOT/app/src/main/kotlin/usb/image/RawWriteOutcome.kt" \
  "$ROOT/app/src/main/kotlin/usb/image/ImageFormat.kt" \
  "$ROOT/app/src/main/kotlin/usb/image/RawRangeProbe.kt" \
  "$ROOT/app/src/main/kotlin/usb/ventoy/WindowsTemplate.kt" \
  "$ROOT/app/src/main/kotlin/usb/ventoy/AutounattendModels.kt" \
  "$ROOT/app/src/main/kotlin/usb/ventoy/Autounattend.kt" \
  "$ROOT/app/src/main/kotlin/usb/ventoy/VentoyModels.kt" \
  "$ROOT/app/src/main/kotlin/usb/ventoy/XmlPath.kt" \
  "$ROOT/app/src/main/kotlin/usb/ventoy/FolderPaths.kt" \
  "$ROOT/app/src/main/kotlin/usb/ui/RestoreInspectionModels.kt" \
  "$ROOT/app/src/main/kotlin/usb/ui/UiModels.kt" \
  "$ROOT/app/src/main/kotlin/usb/ui/FilesPickTransitions.kt" \
  "$ROOT/app/src/main/kotlin/usb/ui/PartitionTransitions.kt" \
  "$ROOT/app/src/main/kotlin/usb/ui/RawTransitions.kt" \
  "$ROOT/app/src/main/kotlin/usb/ui/RescueBackupTransitions.kt" \
  "$ROOT/app/src/main/kotlin/usb/ui/VentoyTransitions.kt" \
  "$ROOT/app/src/main/kotlin/usb/ui/UiReducer.kt" \
  "$ROOT/app/src/test/kotlin/usb/ui/UiStateHostCheck.kt"
if [ -f "$ROOT/app/src/main/kotlin/usb/fs/FilesystemUsage.kt" ]; then
  set -- "$@" "$ROOT/app/src/main/kotlin/usb/fs/FilesystemUsage.kt"
fi
# UiReducer names FatFs absence codes (FR_NO_FILE/FR_NO_PATH); they live in usb.fs/FatFs.kt
# beside the native writers, so the host build needs that file and its collaborators.
for src in \
  "$ROOT/app/src/main/kotlin/usb/fs/UsbFsVolume.kt" \
  "$ROOT/app/src/main/kotlin/usb/fs/FatFs.kt"
do
  test -f "$src" || {
    echo "run-ui-host-check: missing ${src#"$ROOT/"}" >&2
    exit 1
  }
  set -- "$@" "$src"
done
kotlinc -cp "$CP" -d "$OUT" "$@"

kotlin -cp "$OUT:$CP" usb.ui.UiStateHostCheck
