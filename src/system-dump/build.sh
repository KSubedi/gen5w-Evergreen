#!/bin/sh
# Zig provides a reproducible static ARMv7 Linux/musl build with no Android SDK.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
mkdir -p "$ROOT/src/system-dump/build"
"${ZIG:-zig}" cc -target arm-linux-musleabi -mcpu=cortex_a9 -static -Os \
    -Wall -Wextra -Werror -Wl,--build-id=none \
    "$ROOT/src/system-dump/collector.c" -o "$ROOT/src/system-dump/build/collector-arm"
echo "Static ARMv7 collector built."
