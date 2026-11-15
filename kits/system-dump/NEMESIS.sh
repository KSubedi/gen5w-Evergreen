#!/system/bin/sh
# Only the small static helper is staged internally. Image bytes go straight to USB.
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
export LD_LIBRARY_PATH=/vendor/lib:/system/lib
USB=$(pwd)
case "$USB" in /storage/usb*|/mnt/media_rw/usb*) ;; *) echo "Run this kit from a NEMESIS USB mount."; exit 1 ;; esac
# Keep startup errors outside SYSTEM-DUMP: native resume identity checks own that folder.
# Append so a failed retry does not erase the previous diagnosis. Never follow a link.
LAUNCH=$USB/SYSTEM-DUMP-LAUNCH.txt
[ ! -L "$LAUNCH" ] && { [ ! -e "$LAUNCH" ] || [ -f "$LAUNCH" ]; } || exit 1
# A direct write distinguishes USB write failure from log-stream setup failure.
# Avoid exec-only redirection: the first on-car diagnostic retry left an empty log.
echo "=== Evergreen system dump launch v3 ===" >> "$LAUNCH" || exit 1
echo "USB=$USB shell=${KSH_VERSION:-unknown}" >> "$LAUNCH"
run_collector() {
STEP=validate-kit
[ -f collector-arm ] && [ -f collector.md5 ] || { echo "Incomplete collector kit."; return 1; }
STEP=read-checksum
read -r WANT < collector.md5 || return 1
STEP=checksum-usb-helper
GOT=$(md5 collector-arm) || return 1
set -- $GOT
[ "$1" = "$WANT" ] || { echo "Collector binary checksum mismatch. Extract the kit again."; return 1; }
STEP=prepare-internal-helper
STAGE=/data/nemesis/system-dump
mkdir -p "$STAGE" || return 1
# Content-addressed staging never truncates a helper that a previous worker is using.
HELPER=$STAGE/collector-$WANT
if [ ! -f "$HELPER" ]; then
    PART=$HELPER.part.$$
    cat collector-arm > "$PART" || return 1
    GOT=$(md5 "$PART") || return 1
    set -- $GOT
    [ "$1" = "$WANT" ] || return 1
    chmod 700 "$PART" || return 1
    mv "$PART" "$HELPER" || return 1
fi
GOT=$(md5 "$HELPER") || return 1
set -- $GOT
[ "$1" = "$WANT" ] || { echo "Staged helper is damaged; remove $HELPER and rerun."; return 1; }
STEP=start-native
# Only the /system mount entry is diagnostic; do not collect general mount/device data.
while read -r DEV MOUNT TYPE OPTIONS REST; do
    [ "$MOUNT" = /system ] && echo "system mount: $DEV $MOUNT $TYPE $OPTIONS"
done < /proc/mounts
"$HELPER" --start "$USB" "$(getprop ro.build.product)"
RC=$?
if [ "$RC" != 0 ]; then
    echo "Startup failed. Keep this file and any SYSTEM-DUMP/STATUS.txt or collector.log."
fi
return "$RC"
}
run_collector >> "$LAUNCH" 2>&1
RC=$?
echo "Launcher exit=$RC step=$STEP" >> "$LAUNCH"
exit "$RC"
