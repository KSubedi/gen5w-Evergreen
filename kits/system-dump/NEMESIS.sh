#!/system/bin/sh
# Only the small static helper is staged internally. Image bytes go straight to USB.
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
export LD_LIBRARY_PATH=/vendor/lib:/system/lib
USB=$(pwd)
case "$USB" in /storage/usb*|/mnt/media_rw/usb*) ;; *) echo "Run this kit from a NEMESIS USB mount."; exit 1 ;; esac
[ -f collector-arm ] && [ -f collector.md5 ] || { echo "Incomplete collector kit."; exit 1; }
read -r WANT < collector.md5 || exit 1
GOT=$(md5 collector-arm) || exit 1
set -- $GOT
[ "$1" = "$WANT" ] || { echo "Collector binary checksum mismatch. Extract the kit again."; exit 1; }
STAGE=/data/nemesis/system-dump
mkdir -p "$STAGE" || exit 1
# Content-addressed staging never truncates a helper that a previous worker is using.
HELPER=$STAGE/collector-$WANT
if [ ! -f "$HELPER" ]; then
    PART=$HELPER.part.$$
    cat collector-arm > "$PART" || exit 1
    GOT=$(md5 "$PART") || exit 1
    set -- $GOT
    [ "$1" = "$WANT" ] || exit 1
    chmod 700 "$PART" || exit 1
    mv "$PART" "$HELPER" || exit 1
fi
GOT=$(md5 "$HELPER") || exit 1
set -- $GOT
[ "$1" = "$WANT" ] || { echo "Staged helper is damaged; remove $HELPER and rerun."; exit 1; }
"$HELPER" --start "$USB" "$(getprop ro.build.product)"
