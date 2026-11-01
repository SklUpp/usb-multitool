#!/bin/sh
set -e

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
APP_DIR="$REPO_ROOT/app"

JUNIT_JAR="/data/data/com.termux/files/usr/tmp/junit-4.13.2.jar"
HAMCREST_JAR="/data/data/com.termux/files/usr/tmp/hamcrest-core-1.3.jar"
KOTLIN_STDLIB="/data/data/com.termux/files/usr/opt/kotlin/lib/kotlin-stdlib.jar"
COMMONS_COMPRESS_JAR="/data/data/com.termux/files/usr/opt/android-sdk/cmdline-tools/lib/external/org/apache/commons/commons-compress/1.21/commons-compress-1.21.jar"
XZ_JAR="/data/data/com.termux/files/usr/tmp/xz-1.10.jar"
ANDROID_JAR="/data/data/com.termux/files/usr/opt/android-sdk/platforms/android-36/android.jar"

TEST_CP="$JUNIT_JAR:$HAMCREST_JAR:$KOTLIN_STDLIB"
if [ -f "$COMMONS_COMPRESS_JAR" ]; then
    TEST_CP="$TEST_CP:$COMMONS_COMPRESS_JAR"
fi
if [ -f "$XZ_JAR" ]; then
    TEST_CP="$TEST_CP:$XZ_JAR"
fi
if [ -f "$ANDROID_JAR" ]; then
    TEST_CP="$ANDROID_JAR:$TEST_CP"
fi

if [ ! -f "$JUNIT_JAR" ] || [ ! -f "$HAMCREST_JAR" ] || [ ! -f "$KOTLIN_STDLIB" ]; then
    echo "Required jars not found in /data/data/com.termux/files/usr/tmp or /data/data/com.termux/files/usr/opt/kotlin"
    exit 1
fi

BUILD_DIR="${TMPDIR:-$PREFIX/tmp}/ventoy-test-bin-$$"
mkdir -p "$BUILD_DIR"
trap 'rm -rf "$BUILD_DIR"' EXIT

echo "Compiling Kotlin sources..."
kotlinc \
    -cp "$TEST_CP" \
    -d "$BUILD_DIR" \
    $APP_DIR/src/main/kotlin/usb/device/*.kt \
    $APP_DIR/src/main/kotlin/usb/partition/*.kt \
    $APP_DIR/src/main/kotlin/usb/ventoy/*.kt \
    $APP_DIR/src/test/kotlin/usb/device/FakeBotSession.kt \
    $APP_DIR/src/test/kotlin/usb/testutil/RecordingDisk.kt \
    $APP_DIR/src/test/kotlin/usb/ventoy/*.kt

echo "Running Ventoy JUnit tests..."
java -cp "$BUILD_DIR:$TEST_CP" org.junit.runner.JUnitCore usb.ventoy.VentoyLayoutTest usb.ventoy.VentoyReleaseArchiveTest
