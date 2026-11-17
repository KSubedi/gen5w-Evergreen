#!/bin/sh
# Build only our helper classes, never the compile-only OEM stub.
# Needs javac/java, D8_JAR (R8's r8.jar), and baksmali 2.5.2 on PATH.
set -eu
cd "$(dirname "$0")"
: "${D8_JAR:?Set D8_JAR to the Android R8/D8 jar}"
mkdir -p build
stage=$(mktemp -d "$PWD/build/.compile.XXXXXX")
trap 'rm -rf "$stage"' EXIT HUP INT TERM
mkdir -p "$stage/classes" "$stage/dex"
javac --release 7 -Xlint:-options -d "$stage/classes" \
    stubs/com/hkmc/cluster/NavigationCluster.java stubs/android/os/SystemClock.java stubs/android/util/Log.java java/org/evergreen/carplay/*.java
JAVA_LIB=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.home = //p')
java -cp "$D8_JAR" com.android.tools.r8.D8 --min-api 19 --lib "$JAVA_LIB" \
    --output "$stage/dex" "$stage"/classes/org/evergreen/carplay/*.class
baksmali d "$stage/dex/classes.dex" -o "$stage/smali"
[ ! -d "$stage/smali/com" ] && [ ! -d "$stage/smali/android" ] || { echo 'Platform stub leaked into dex' >&2; exit 1; }
[ "$(find "$stage/smali" -name '*.smali' | wc -l)" -eq 11 ] || exit 1
rm -rf build/smali
mv "$stage/smali" build/smali
echo "AR helpers built: $PWD/build/smali"
