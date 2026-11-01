#!/usr/bin/env sh
# Run JUnit ConfirmationTest on host without Gradle.
set -eu
ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
OUT="${TMPDIR:-${PREFIX:-/tmp}/tmp}/usb-ui-junit-$$"
mkdir -p "$OUT"
cleanup() { rm -rf "$OUT"; }
trap cleanup EXIT

STDLIB="/data/data/com.termux/files/usr/opt/kotlin/lib/kotlin-stdlib.jar"
JDK7="/data/data/com.termux/files/usr/opt/kotlin/lib/kotlin-stdlib-jdk7.jar"
JDK8="/data/data/com.termux/files/usr/opt/kotlin/lib/kotlin-stdlib-jdk8.jar"
JUNIT="/data/data/com.termux/files/usr/tmp/junit-4.13.2.jar"
HAMCREST="/data/data/com.termux/files/usr/tmp/hamcrest-core-1.3.jar"
CP="$STDLIB:$JDK7:$JDK8:$JUNIT:$HAMCREST"

kotlinc \
  -cp "$CP" \
  -d "$OUT" \
  "$ROOT/app/src/main/kotlin/usb/ui/UiModels.kt" \
  "$ROOT/app/src/main/kotlin/usb/ui/UiReducer.kt" \
  "$ROOT/app/src/test/kotlin/usb/ui/ConfirmationTest.kt"

java -cp "$OUT:$CP" org.junit.runner.JUnitCore \
  usb.ui.ConfirmationTest \
  usb.ui.UiStateMachineTest
