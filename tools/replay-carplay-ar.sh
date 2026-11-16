#!/bin/sh
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT HUP INT TERM
javac --release 7 -Xlint:-options -d "$tmp" \
 "$ROOT"/src/carplay-ar/stubs/com/hkmc/cluster/NavigationCluster.java \
 "$ROOT"/src/carplay-ar/stubs/android/os/SystemClock.java \
 "$ROOT"/src/carplay-ar/stubs/android/util/Log.java \
 "$ROOT"/src/carplay-ar/java/org/evergreen/carplay/*.java \
 "$ROOT"/src/carplay-ar/test/org/evergreen/carplay/ArHudReplay.java
java -cp "$tmp" org.evergreen.carplay.ArHudReplay "$@"
