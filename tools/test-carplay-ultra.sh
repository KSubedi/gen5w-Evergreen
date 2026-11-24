#!/bin/sh
# CarPlay Ultra-Lite test port (src/carplay-ultra): JVM checks of the HMG-API stand-in, and that
# CarPlay Ultra-Lite's unchanged source plus the stand-in and starter compile for Android 4.4.
# Needs javac/java and ANDROID_HOME with platforms/android-19.
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
S=$ROOT/src/carplay-ultra
ANDROID_JAR=$ANDROID_HOME/platforms/android-19/android.jar
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
# the test's android.util.Log stand-in comes first, android.jar only for the other types
javac -nowarn -source 8 -target 8 -encoding UTF-8 -cp "$ANDROID_JAR" -d "$T/test" \
    $(find "$S/shim" "$S/test" -name '*.java') > "$T/javac1.log" 2>&1 || { cat "$T/javac1.log"; exit 1; }
java -cp "$T/test:$ANDROID_JAR" hmgapi.hook.ShimTest
javac -nowarn -source 8 -target 8 -encoding UTF-8 -bootclasspath "$ANDROID_JAR" -d "$T/android" \
    $(find "$S/upstream/mod/src" "$S/shim" "$S/glue" -name '*.java') > "$T/javac2.log" 2>&1 || { cat "$T/javac2.log"; exit 1; }
[ -f "$T/android/org/uwp/carplayultra/Ultra.class" ] && [ -f "$T/android/ioniq6/cpclimate/CpClimate.class" ]
echo "ok    CarPlay Ultra-Lite 1.3.3 + stand-in + starter compile against android-19"
