#!/usr/bin/env sh
# Host Ventoy JUnit without Gradle. Pure JVM + local/Gradle-cache jars.
set -eu

ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
APP="$ROOT/app"
OUT="${TMPDIR:-${PREFIX:-/tmp}/tmp}/ventoy-junit-$$"
mkdir -p "$OUT"
cleanup() { rm -rf "$OUT"; }
trap cleanup EXIT

PREFIX_ROOT="${PREFIX:-/data/data/com.termux/files/usr}"
HOME_ROOT="${HOME:-/data/data/com.termux/files/home}"
KOTLIN_LIB="$PREFIX_ROOT/opt/kotlin/lib"
GRADLE_MODULES="$HOME_ROOT/.gradle/caches/modules-2/files-2.1"

# Newest matching jar under roots; empty if none. No content-hash path segments required.
find_jar() {
  name="$1"
  shift
  found=""
  for root in "$@"; do
    [ -d "$root" ] || continue
    # Prefer non-sources/javadoc; sort by mtime so cache upgrades win.
    cand=$(find "$root" -type f -name "$name" ! -name '*-sources.jar' ! -name '*-javadoc.jar' 2>/dev/null \
      | while IFS= read -r p; do
          # skip transform/ junk copies when possible
          case "$p" in
            */transforms/*|*/transformed/*) continue ;;
          esac
          printf '%s\t%s\n' "$(stat -c '%Y' "$p" 2>/dev/null || stat -f '%m' "$p" 2>/dev/null || echo 0)" "$p"
        done | sort -n | tail -n 1 | cut -f2-)
    if [ -n "$cand" ] && [ -f "$cand" ]; then
      found="$cand"
      break
    fi
  done
  printf '%s' "$found"
}

STDLIB="$KOTLIN_LIB/kotlin-stdlib.jar"
JDK7="$KOTLIN_LIB/kotlin-stdlib-jdk7.jar"
JDK8="$KOTLIN_LIB/kotlin-stdlib-jdk8.jar"
COROUTINES="$KOTLIN_LIB/kotlinx-coroutines-core-jvm.jar"
if [ ! -f "$COROUTINES" ]; then
  COROUTINES=$(find_jar 'kotlinx-coroutines-core-jvm-*.jar' \
    "$GRADLE_MODULES/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm" "$KOTLIN_LIB")
fi
COROUTINES_TEST=$(find_jar 'kotlinx-coroutines-test-jvm-*.jar' \
  "$GRADLE_MODULES/org.jetbrains.kotlinx/kotlinx-coroutines-test-jvm" \
  "$KOTLIN_LIB" "$PREFIX_ROOT/tmp")
JUNIT=$(find_jar 'junit-4*.jar' "$PREFIX_ROOT/tmp" \
  "$GRADLE_MODULES/junit/junit")
HAMCREST=$(find_jar 'hamcrest-core-*.jar' "$PREFIX_ROOT/tmp" \
  "$GRADLE_MODULES/org.hamcrest/hamcrest-core")
# Fixed names in PREFIX tmp (CI host jars).
if [ -z "$HAMCREST" ] && [ -f "$PREFIX_ROOT/tmp/hamcrest-core-1.3.jar" ]; then
  HAMCREST="$PREFIX_ROOT/tmp/hamcrest-core-1.3.jar"
fi
if [ -z "$JUNIT" ] && [ -f "$PREFIX_ROOT/tmp/junit-4.13.2.jar" ]; then
  JUNIT="$PREFIX_ROOT/tmp/junit-4.13.2.jar"
fi
COMMONS_COMPRESS=$(find_jar 'commons-compress-*.jar' \
  "$PREFIX_ROOT/opt/android-sdk" \
  "$GRADLE_MODULES/org.apache.commons/commons-compress" \
  "$PREFIX_ROOT/tmp")
XZ_JAR=$(find_jar 'xz-*.jar' \
  "$GRADLE_MODULES/org.tukaani/xz" "$PREFIX_ROOT/tmp")
# Platform jar: highest android-* with android.jar (no fixed API level).
ANDROID_JAR=""
if [ -d "$PREFIX_ROOT/opt/android-sdk/platforms" ]; then
  ANDROID_JAR=$(find "$PREFIX_ROOT/opt/android-sdk/platforms" -mindepth 2 -maxdepth 2 -type f -name 'android.jar' 2>/dev/null \
    | sort | tail -n 1)
fi

for jar in \
  "STDLIB:$STDLIB" \
  "COROUTINES:$COROUTINES" \
  "COROUTINES_TEST:$COROUTINES_TEST" \
  "JUNIT:$JUNIT" \
  "HAMCREST:$HAMCREST" \
  "COMMONS_COMPRESS:$COMMONS_COMPRESS" \
  "XZ_JAR:$XZ_JAR" \
  "ANDROID_JAR:$ANDROID_JAR"
do
  label=${jar%%:*}
  path=${jar#*:}
  if [ -z "$path" ] || [ ! -f "$path" ]; then
    echo "run-ventoy-junit: missing $label (searched kotlin lib / Gradle cache / PREFIX tmp / android-sdk)" >&2
    exit 1
  fi
done

# jdk7/jdk8 optional if embedded in newer stdlib; add when present.
CP="$ANDROID_JAR:$STDLIB"
[ -f "$JDK7" ] && CP="$CP:$JDK7"
[ -f "$JDK8" ] && CP="$CP:$JDK8"
CP="$CP:$COROUTINES:$COROUTINES_TEST:$JUNIT:$HAMCREST:$COMMONS_COMPRESS:$XZ_JAR"

# --- tests: every *.kt under usb/ventoy (wildcard). No silent drop. ---
VENTOY_TEST_DIR="$APP/src/test/kotlin/usb/ventoy"
if [ ! -d "$VENTOY_TEST_DIR" ]; then
  echo "run-ventoy-junit: missing $VENTOY_TEST_DIR" >&2
  exit 1
fi

# Every *.kt under ventoy tests is compiled. JUnitCore gets files that declare @Test
# (helpers without @Test stay compile-only — no hardcoded suite denylist).
TEST_CLASSES=""
class_count=0
run_count=0
for f in "$VENTOY_TEST_DIR"/*.kt; do
  [ -f "$f" ] || continue
  class_count=$((class_count + 1))
  base=$(basename "$f" .kt)
  if grep -qE '^[[:space:]]*@Test([[:space:]]|$|\()' "$f" 2>/dev/null; then
    TEST_CLASSES="$TEST_CLASSES usb.ventoy.$base"
    run_count=$((run_count + 1))
  fi
done
if [ "$class_count" -eq 0 ]; then
  echo "run-ventoy-junit: no tests under $VENTOY_TEST_DIR" >&2
  exit 1
fi
if [ "$run_count" -eq 0 ]; then
  echo "run-ventoy-junit: no @Test methods under $VENTOY_TEST_DIR" >&2
  exit 1
fi

# Shared fixtures required by current suites.
for fx in \
  "$APP/src/test/kotlin/usb/device/FakeBotSession.kt" \
  "$APP/src/test/kotlin/usb/testutil/RecordingDisk.kt" \
  "$APP/src/test/kotlin/usb/testutil/InMemoryUsbVolume.kt"
do
  test -f "$fx" || {
    echo "run-ventoy-junit: missing fixture $fx" >&2
    exit 1
  }
done

# Production sources needed by ventoy/* + fixtures.
# device: skip Android USB session/policy/channel.
# service: OperationExecutor only (DiskService is Android Service).
# files/*: Android ContentResolver — tests do not import.
# operation: skip UI bridges (OperationCoordination/LifecyclePolicy → usb.ui).
set -- \
  "$APP/src/main/kotlin/usb/EngineMissingException.kt" \
  "$APP/src/main/kotlin/usb/FormatBytes.kt" \
  "$APP/src/main/kotlin/usb/device/BlockDevice.kt" \
  "$APP/src/main/kotlin/usb/device/BlockGeometry.kt" \
  "$APP/src/main/kotlin/usb/device/BotConstants.kt" \
  "$APP/src/main/kotlin/usb/device/BotSessionImpl.kt" \
  "$APP/src/main/kotlin/usb/device/BotTransport.kt" \
  "$APP/src/main/kotlin/usb/device/DiskException.kt" \
  "$APP/src/main/kotlin/usb/device/ScsiCommand.kt" \
  "$APP/src/main/kotlin/usb/device/UsbDisk.kt" \
  "$APP/src/main/kotlin/usb/device/WriteTrackingBlockDevice.kt" \
  "$APP/src/main/kotlin/usb/partition/ConfirmedPartitionLookup.kt" \
  "$APP/src/main/kotlin/usb/partition/GptBackup.kt" \
  "$APP/src/main/kotlin/usb/partition/GptTable.kt" \
  "$APP/src/main/kotlin/usb/partition/MbrTable.kt" \
  "$APP/src/main/kotlin/usb/partition/PartitionClassification.kt" \
  "$APP/src/main/kotlin/usb/partition/PartitionEditor.kt" \
  "$APP/src/main/kotlin/usb/partition/PartitionModels.kt" \
  "$APP/src/main/kotlin/usb/partition/PartitionTable.kt" \
  "$APP/src/main/kotlin/usb/partition/PartitionWriter.kt" \
  "$APP/src/main/kotlin/usb/partition/RawRangePlan.kt" \
  "$APP/src/main/kotlin/usb/partition/TableInitializer.kt" \
  "$APP/src/main/kotlin/usb/partition/VirtualPartition.kt" \
  "$APP/src/main/kotlin/usb/fs/BlockDeviceFatCallback.kt" \
  "$APP/src/main/kotlin/usb/fs/CloseFailure.kt" \
  "$APP/src/main/kotlin/usb/fs/ExfatFsck.kt" \
  "$APP/src/main/kotlin/usb/fs/ExtFs.kt" \
  "$APP/src/main/kotlin/usb/fs/FatFs.kt" \
  "$APP/src/main/kotlin/usb/fs/Filesystem.kt" \
  "$APP/src/main/kotlin/usb/fs/FilesystemId.kt" \
  "$APP/src/main/kotlin/usb/fs/FilesystemUsage.kt" \
  "$APP/src/main/kotlin/usb/fs/FilesystemUsageProbe.kt" \
  "$APP/src/main/kotlin/usb/fs/FormatCatalog.kt" \
  "$APP/src/main/kotlin/usb/fs/Formatter.kt" \
  "$APP/src/main/kotlin/usb/fs/NtfsFs.kt" \
  "$APP/src/main/kotlin/usb/fs/PartitionVolumeSession.kt" \
  "$APP/src/main/kotlin/usb/fs/UsbFsVolume.kt" \
  "$APP/src/main/kotlin/usb/operation/DestructiveDiskTail.kt" \
  "$APP/src/main/kotlin/usb/operation/DiskOperation.kt" \
  "$APP/src/main/kotlin/usb/service/OperationExecutor.kt"

# All ventoy production sources (wildcard — new files not silently omitted).
VENTOY_MAIN_DIR="$APP/src/main/kotlin/usb/ventoy"
if [ ! -d "$VENTOY_MAIN_DIR" ]; then
  echo "run-ventoy-junit: missing $VENTOY_MAIN_DIR" >&2
  exit 1
fi
for f in "$VENTOY_MAIN_DIR"/*.kt; do
  [ -f "$f" ] || continue
  set -- "$@" "$f"
done

# Shared fixtures + every ventoy test source.
set -- "$@" \
  "$APP/src/test/kotlin/usb/device/FakeBotSession.kt" \
  "$APP/src/test/kotlin/usb/testutil/RecordingDisk.kt" \
  "$APP/src/test/kotlin/usb/testutil/InMemoryUsbVolume.kt"
for f in "$VENTOY_TEST_DIR"/*.kt; do
  [ -f "$f" ] || continue
  set -- "$@" "$f"
done

for f in "$@"; do
  test -f "$f" || {
    echo "run-ventoy-junit: missing source $f" >&2
    exit 1
  }
done

echo "Compiling Ventoy host JUnit sources..."
echo "jars: coroutines-test=$COROUTINES_TEST xz=$XZ_JAR compress=$COMMONS_COMPRESS android=$ANDROID_JAR"
kotlinc -cp "$CP" -d "$OUT" "$@"

# JUnitCore class list from wildcard + @Test scan above.
# shellcheck disable=SC2086
set -- $TEST_CLASSES
if [ "$#" -eq 0 ]; then
  echo "run-ventoy-junit: no JUnit classes with @Test under $VENTOY_TEST_DIR" >&2
  exit 1
fi

echo "Running Ventoy JUnit tests ($# classes)..."
# shellcheck disable=SC2086
java -cp "$OUT:$CP" org.junit.runner.JUnitCore "$@"
