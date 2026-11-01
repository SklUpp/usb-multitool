#!/bin/sh
set -e

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
APP_DIR="$REPO_ROOT/app"

JUNIT_JAR="/data/data/com.termux/files/usr/tmp/junit-4.13.2.jar"
HAMCREST_JAR="/data/data/com.termux/files/usr/tmp/hamcrest-core-1.3.jar"
KOTLIN_STDLIB="/data/data/com.termux/files/usr/opt/kotlin/lib/kotlin-stdlib.jar"

if [ ! -f "$JUNIT_JAR" ] || [ ! -f "$HAMCREST_JAR" ] || [ ! -f "$KOTLIN_STDLIB" ]; then
    echo "Required jars not found in /data/data/com.termux/files/usr/tmp or /data/data/com.termux/files/usr/opt/kotlin"
    exit 1
fi

BUILD_DIR="${TMPDIR:-$PREFIX/tmp}/ventoy-test-bin-$$"
mkdir -p "$BUILD_DIR"
trap 'rm -rf "$BUILD_DIR"' EXIT

echo "Compiling Kotlin sources..."
kotlinc \
    -cp "$JUNIT_JAR:$HAMCREST_JAR:$KOTLIN_STDLIB" \
    -d "$BUILD_DIR" \
    "$APP_DIR/src/main/kotlin/usb/device/BlockDevice.kt" \
    "$APP_DIR/src/main/kotlin/usb/partition/PartitionTable.kt" \
    "$APP_DIR/src/main/kotlin/usb/ventoy/VentoyAssets.kt" \
    "$APP_DIR/src/main/kotlin/usb/ventoy/VentoyLayout.kt" \
    "$APP_DIR/src/test/kotlin/usb/testutil/RecordingDisk.kt" \
    "$APP_DIR/src/test/kotlin/usb/ventoy/VentoyLayoutTest.kt"

echo "Running Ventoy JUnit tests..."
java -cp "$BUILD_DIR:$JUNIT_JAR:$HAMCREST_JAR:$KOTLIN_STDLIB" org.junit.runner.JUnitCore usb.ventoy.VentoyLayoutTest
