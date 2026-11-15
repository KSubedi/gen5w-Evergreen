#!/bin/sh
# Build the Wireless CarPlay/AA NEMESIS package. Everything comes from this repo:
# scripts, lpatch, recipes and the per-car patch sets in patches/. No modified
# app is shipped - the head unit rebuilds them from its own stock files.
# usage: build-package.sh [output zip]
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT=${1:-$ROOT/dist/Evergreen_Wireless_CarPlay_AA.zip}
# A private, generated AR patch tree can be packaged for mock/on-car testing.
# Defaults to the committed tree; the source manifest checks still apply.
PATCHES=${EVERGREEN_PATCHES_DIR:-$ROOT/patches}
[ -f "$PATCHES/cars" ] || { echo "missing patch tree: $PATCHES" >&2; exit 1; }
cmp -s "$PATCHES/cars" "$ROOT/patches/cars" || {
    echo "override patch tree changes the cars table; update the source descriptor first" >&2
    exit 1
}
case "$OUT" in /*) ;; *) OUT=$(pwd)/$OUT ;; esac
# the HU's shell (mksh) has no printf, but the host shells the tests run in do;
# the only allowed use is putline's host fallback
if find "$ROOT/packages/wireless-carplay-aa" -type f -name '*.sh' -exec grep -nH 'printf' {} + \
    | grep -vE ':[0-9]+:[[:space:]]*#' | grep -vF "printf '%s\n' \"\$1\""; then
    echo "build-package: printf in package scripts (not on the head unit) - use echo or putline" >&2
    exit 1
fi
python3 "$ROOT/tools/check-descriptor.py" >&2
STAGE=$(mktemp -d)
cp -R "$ROOT/packages/wireless-carplay-aa/." "$STAGE/inner"
rm -f "$STAGE/inner/README.md"
mkdir -p "$STAGE/inner/tools" "$STAGE/inner/recipes"
cp "$ROOT/tools/lpatch/lpatch" "$STAGE/inner/tools/lpatch"
cp "$ROOT"/recipes/*.recipe "$STAGE/inner/recipes/"
cp -R "$PATCHES" "$STAGE/inner/patches"
rm -f "$STAGE/inner/patches/README.md"
# which source this package was built from; logged at install, read by the Leafia kit
echo "$(git -C "$ROOT" describe --always --dirty 2>/dev/null || echo unknown) $(date -u +%Y-%m-%d)" > "$STAGE/inner/BUILD"
mkdir -p "$STAGE/outer/packages"
(cd "$STAGE/inner" && zip -qrX "$STAGE/outer/packages/wireless.carplay.aa.zip" .)
printf '[nemesis]\nversion=1.0\ntype=install\npath=packages/wireless.carplay.aa.zip\n' > "$STAGE/outer/NEMESIS.data"
mkdir -p "$(dirname "$OUT")" && rm -f "$OUT"
(cd "$STAGE/outer" && zip -qrX "$OUT" NEMESIS.data packages)
rm -r "$STAGE"
echo "$OUT"
