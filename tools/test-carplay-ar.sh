#!/bin/sh
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT HUP INT TERM
javac --release 7 -Xlint:-options -d "$tmp" \
    "$ROOT/src/carplay-ar/stubs/com/hkmc/cluster/NavigationCluster.java" \
    "$ROOT/src/carplay-ar/stubs/android/os/SystemClock.java" \
    "$ROOT/src/carplay-ar/stubs/android/util/Log.java" \
    "$ROOT"/src/carplay-ar/java/org/evergreen/carplay/*.java \
    "$ROOT"/src/carplay-ar/test/org/evergreen/carplay/*.java
java -cp "$tmp" org.evergreen.carplay.ArHudTest
java -cp "$tmp" org.evergreen.carplay.ArHudAdvancedTest
java -cp "$tmp" org.evergreen.carplay.ArHudRuntimeTest
java -cp "$tmp" org.evergreen.carplay.ArHudReplayTest
java -cp "$tmp" org.evergreen.carplay.ArHudDebugTest
java -cp "$tmp" org.evergreen.carplay.ArHudAutoTest
python3 "$ROOT/tools/test-carplay-ar.py"
python3 "$ROOT/tools/test-carplay-ar-advanced.py"
python3 "$ROOT/tools/test-analyze-ar-log.py"

python3 "$ROOT/tools/build-ar-finish.py"
python3 "$ROOT/tools/test-ar-finish.py"
