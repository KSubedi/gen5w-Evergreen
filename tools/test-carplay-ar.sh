#!/bin/sh
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT HUP INT TERM
javac --release 7 -Xlint:-options -d "$tmp" \
    "$ROOT/src/carplay-ar/stubs/com/hkmc/cluster/NavigationCluster.java" \
    "$ROOT"/src/carplay-ar/java/org/evergreen/carplay/*.java \
    "$ROOT/src/carplay-ar/test/org/evergreen/carplay/ArHudTest.java"
java -cp "$tmp" org.evergreen.carplay.ArHudTest
python3 "$ROOT/tools/test-carplay-ar.py"
