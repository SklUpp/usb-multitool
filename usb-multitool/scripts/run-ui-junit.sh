#!/usr/bin/env sh
# Run the pure-UI JUnit suites on host without Gradle.
set -eu
ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
OUT="${TMPDIR:-${PREFIX:-/tmp}/tmp}/usb-ui-junit-$$"
mkdir -p "$OUT"
cleanup() { rm -rf "$OUT"; }
trap cleanup EXIT

STDLIB="/data/data/com.termux/files/usr/opt/kotlin/lib/kotlin-stdlib.jar"
JDK7="/data/data/com.termux/files/usr/opt/kotlin/lib/kotlin-stdlib-jdk7.jar"
JDK8="/data/data/com.termux/files/usr/opt/kotlin/lib/kotlin-stdlib-jdk8.jar"
COROUTINES="/data/data/com.termux/files/usr/opt/kotlin/lib/kotlinx-coroutines-core-jvm.jar"
JUNIT="/data/data/com.termux/files/usr/tmp/junit-4.13.2.jar"
HAMCREST="/data/data/com.termux/files/usr/tmp/hamcrest-core-1.3.jar"
CP="$STDLIB:$JDK7:$JDK8:$COROUTINES:$JUNIT:$HAMCREST"

# Fail with one sentence instead of a page of unresolved-reference errors when the host jars
# are not installed in this environment; CI provides them.
for jar in "$STDLIB" "$JUNIT" "$HAMCREST"; do
  if [ ! -f "$jar" ]; then
    echo "run-ui-junit: missing $jar (host JUnit jars are only installed in CI)" >&2
    exit 1
  fi
done

# Every suite that must run here; a missing file is a merge error, not a silent skip.
for suite in \
  FormatPickerTest \
  PartitionRenameUiTest \
  SchemeInitUiTest \
  ConfirmationTest \
  BackupFolderUiTest \
  RestoreInspectionTest \
  OperationProgressMapperTest \
  StringResourceParityTest \
  ShellLogBehaviorTest \
  FilesystemUsageUiTest \
  FilesScreenModelTest \
  RawRangeUiStateTest \
  AutounattendPathTest \
  AutounattendReloadUiTest \
  VentoyUiStateTest \
  VentoySettingsUiTest \
  PhoneImportUiTest \
  FilesPickUiTest \
  UiEffectTypingTest \
  ServiceOperationMappingTest \
  TargetDiskLayoutEditorTest
do
  test -f "$ROOT/app/src/test/kotlin/usb/ui/$suite.kt" || {
    echo "run-ui-junit: missing app/src/test/kotlin/usb/ui/$suite.kt" >&2
    exit 1
  }
done

# Volume-label rules live in usb.fs, next to the native writers they mirror.
test -f "$ROOT/app/src/test/kotlin/usb/fs/VolumeLabelTest.kt" || {
  echo "run-ui-junit: missing app/src/test/kotlin/usb/fs/VolumeLabelTest.kt" >&2
  exit 1
}
test -f "$ROOT/app/src/test/kotlin/usb/ventoy/AutounattendTest.kt" || {
  echo "run-ui-junit: missing app/src/test/kotlin/usb/ventoy/AutounattendTest.kt" >&2
  exit 1
}

# FilesystemUsage.kt is owned by the measurement backend; add it when it exists so the suite
# does not break the moment it lands or before it does.
set -- \
  "$ROOT/app/src/main/kotlin/usb/FormatBytes.kt" \
  "$ROOT/app/src/main/kotlin/usb/diagnostics/ScanReport.kt" \
  "$ROOT/app/src/main/kotlin/usb/diagnostics/RescueMap.kt" \
  "$ROOT/app/src/main/kotlin/usb/diagnostics/BackupArchive.kt" \
  "$ROOT/app/src/main/kotlin/usb/operation/DiskOperation.kt" \
  "$ROOT/app/src/main/kotlin/usb/multitool/GptCriticalBackup.kt" \
  "$ROOT/app/src/main/kotlin/usb/fs/FilesystemId.kt" \
  "$ROOT/app/src/main/kotlin/usb/fs/FormatCatalog.kt" \
  "$ROOT/app/src/main/kotlin/usb/partition/PartitionModels.kt" \
  "$ROOT/app/src/main/kotlin/usb/partition/PartitionClassification.kt" \
  "$ROOT/app/src/main/kotlin/usb/device/BlockDevice.kt" \
  "$ROOT/app/src/main/kotlin/usb/partition/MbrTable.kt" \
  "$ROOT/app/src/main/kotlin/usb/partition/GptTable.kt" \
  "$ROOT/app/src/main/kotlin/usb/partition/PartitionTable.kt" \
  "$ROOT/app/src/main/kotlin/usb/partition/GptBackup.kt" \
  "$ROOT/app/src/main/kotlin/usb/partition/RawRangePlan.kt" \
  "$ROOT/app/src/main/kotlin/usb/image/RawWriteOutcome.kt" \
  "$ROOT/app/src/main/kotlin/usb/image/ImageFormat.kt" \
  "$ROOT/app/src/main/kotlin/usb/image/RawRangeProbe.kt" \
  "$ROOT/app/src/main/kotlin/usb/ventoy/WindowsTemplate.kt" \
  "$ROOT/app/src/main/kotlin/usb/ventoy/AutounattendModels.kt" \
  "$ROOT/app/src/main/kotlin/usb/ventoy/VentoyModels.kt" \
  "$ROOT/app/src/main/kotlin/usb/ventoy/Autounattend.kt" \
  "$ROOT/app/src/main/kotlin/usb/ventoy/XmlPath.kt" \
  "$ROOT/app/src/main/kotlin/usb/ventoy/FolderPaths.kt" \
  "$ROOT/app/src/main/kotlin/usb/ui/RestoreInspectionModels.kt" \
  "$ROOT/app/src/main/kotlin/usb/ui/RestoreSourceInspector.kt" \
  "$ROOT/app/src/main/kotlin/usb/ui/UiModels.kt" \
  "$ROOT/app/src/main/kotlin/usb/ui/OperationProgressMapper.kt" \
  "$ROOT/app/src/main/kotlin/usb/ui/UiReducer.kt" \
  "$ROOT/app/src/main/kotlin/usb/ui/WindowsImportLaunchQueue.kt" \
  "$ROOT/app/src/test/kotlin/usb/ui/FormatPickerTest.kt" \
  "$ROOT/app/src/test/kotlin/usb/ui/PartitionRenameUiTest.kt" \
  "$ROOT/app/src/test/kotlin/usb/ui/SchemeInitUiTest.kt" \
  "$ROOT/app/src/test/kotlin/usb/ui/ConfirmationTest.kt" \
  "$ROOT/app/src/test/kotlin/usb/ui/BackupFolderUiTest.kt" \
  "$ROOT/app/src/test/kotlin/usb/ui/RestoreInspectionTest.kt" \
  "$ROOT/app/src/test/kotlin/usb/partition/SyntheticGpt.kt" \
  "$ROOT/app/src/test/kotlin/usb/partition/DiskFixture.kt" \
  "$ROOT/app/src/test/kotlin/usb/ui/OperationProgressMapperTest.kt" \
  "$ROOT/app/src/test/kotlin/usb/ui/StringResourceParityTest.kt" \
  "$ROOT/app/src/test/kotlin/usb/ui/ShellLogBehaviorTest.kt" \
  "$ROOT/app/src/test/kotlin/usb/ui/RawRangeUiStateTest.kt" \
  "$ROOT/app/src/test/kotlin/usb/ui/FilesystemUsageUiTest.kt" \
  "$ROOT/app/src/test/kotlin/usb/ui/FilesScreenModelTest.kt" \
  "$ROOT/app/src/test/kotlin/usb/ui/AutounattendPathTest.kt" \
  "$ROOT/app/src/test/kotlin/usb/ui/AutounattendReloadUiTest.kt" \
  "$ROOT/app/src/test/kotlin/usb/ui/VentoyUiStateTest.kt" \
  "$ROOT/app/src/test/kotlin/usb/ui/VentoySettingsUiTest.kt" \
  "$ROOT/app/src/test/kotlin/usb/ui/PhoneImportUiTest.kt" \
  "$ROOT/app/src/test/kotlin/usb/ui/FilesPickUiTest.kt" \
  "$ROOT/app/src/test/kotlin/usb/ui/UiEffectTypingTest.kt" \
  "$ROOT/app/src/test/kotlin/usb/ui/ServiceOperationMappingTest.kt" \
  "$ROOT/app/src/test/kotlin/usb/ui/TargetDiskLayoutEditorTest.kt" \
  "$ROOT/app/src/test/kotlin/usb/fs/VolumeLabelTest.kt" \
  "$ROOT/app/src/test/kotlin/usb/ventoy/AutounattendTest.kt" \
  "$ROOT/app/src/test/kotlin/usb/ventoy/FolderPathsTest.kt" \
  "$ROOT/app/src/test/kotlin/usb/ventoy/XmlPathTest.kt"
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
    echo "run-ui-junit: missing ${src#"$ROOT/"}" >&2
    exit 1
  }
  set -- "$@" "$src"
done
kotlinc -cp "$CP" -d "$OUT" "$@"

# Run from the module root so resource-parsing tests resolve app/src/main/res.
cd "$ROOT"
java -cp "$OUT:$CP" org.junit.runner.JUnitCore \
  usb.ui.FormatPickerTest \
  usb.ui.PartitionRenameUiTest \
  usb.ui.SchemeInitUiTest \
  usb.ui.ConfirmationTest \
  usb.ui.BackupFolderUiTest \
  usb.ui.RestoreInspectionTest \
  usb.ui.OperationProgressMapperTest \
  usb.ui.UiStateMachineTest \
  usb.ui.StringResourceParityTest \
  usb.ui.ShellLogBehaviorTest \
  usb.ui.RawRangeUiStateTest \
  usb.ui.FilesystemUsageUiTest \
  usb.ui.FilesScreenModelTest \
  usb.ui.AutounattendPathTest \
  usb.ui.AutounattendReloadUiTest \
  usb.ui.VentoyUiStateTest \
  usb.ui.VentoySettingsUiTest \
  usb.ui.PhoneImportUiTest \
  usb.ui.FilesPickUiTest \
  usb.ui.UiEffectTypingTest \
  usb.ui.ServiceOperationMappingTest \
  usb.ui.TargetDiskLayoutEditorTest \
  usb.fs.VolumeLabelTest \
  usb.ventoy.AutounattendTest \
  usb.ventoy.FolderPathsTest \
  usb.ventoy.XmlPathTest
