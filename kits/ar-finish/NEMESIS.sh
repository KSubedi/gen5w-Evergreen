#!/system/bin/sh
# Freeze only our recorder, verify USB copies, then remove only those verified logs.
SOURCE=${EVERGREEN_AR_SOURCE_ROOT:-}
if [ -z "$SOURCE" ]; then
    export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
fi
PKG=$SOURCE/data/nemesis/apps/wireless.carplay.aa
DEBUG=$SOURCE/data/nemesis/wireless.carplay.aa-ar-debug
OUT=AR-DEBUG-FINISHED
SERVICE=wireless.carplay.aa/ar-debug
fail() { echo "ERROR: $1. Internal logs have not been deleted."; exit 1; }
ar_digest() {
    _value=$(md5 "$1" 2>/dev/null) || return 1
    set -- $_value
    [ "${#1}" = 32 ] || return 1
    case "$1" in *[!a-fA-F0-9]*) return 1 ;; esac
    echo "$1"
}
copy_verified() {
    _before=$(ar_digest "$1") || return 1
    cp "$1" "$2" || return 1
    _after=$(ar_digest "$1") || return 1
    _copy=$(ar_digest "$2") || return 1
    [ "$_before" = "$_after" ] && [ "$_before" = "$_copy" ] || return 1
    echo "$_copy  $2" >> "$OUT/VERIFIED.txt"
}
case "$(getprop ro.build.product)" in CVEV.USA.*.260507) ;; *) fail "Unsupported firmware" ;; esac
[ -f "$PKG/AR_INSTALL.json" ] && grep -q '"version": "1.2.0.104"' "$PKG/AR_INSTALL.json" || fail "This finish kit requires AR 1.2.0.104"
mkdir -p "$OUT" || fail "Cannot create USB export folder"
: > "$OUT/VERIFIED.txt" || fail "USB not writable"
read -r ORIGINAL < original-md5.txt || fail "Missing recorder fingerprint"
INSTALLED=$(ar_digest "$PKG/svc/ar-debug.sh") || fail "Cannot checksum installed recorder"
STOPPED=$(ar_digest stopped-recorder.sh) || fail "Cannot checksum idle recorder"
[ "$INSTALLED" = "$ORIGINAL" ] || [ "$INSTALLED" = "$STOPPED" ] || fail "Recorder differs from the tested version"
copy_verified "$PKG/svc/ar-debug.sh" "$OUT/RECORDER-BEFORE.sh" || fail "Cannot back up recorder on USB"
# Only our own service script is changed; this prevents log capture returning at reboot.
cp stopped-recorder.sh "$PKG/svc/ar-debug.sh.new" && chmod 755 "$PKG/svc/ar-debug.sh.new" || fail "Cannot stage idle recorder"
[ "$(ar_digest "$PKG/svc/ar-debug.sh.new")" = "$STOPPED" ] || fail "Idle recorder copy mismatch"
mv "$PKG/svc/ar-debug.sh.new" "$PKG/svc/ar-debug.sh" || fail "Cannot disable recorder persistently"
sync || fail "Cannot flush recorder disable change"
nemesis service status "$SERVICE" > "$OUT/SERVICE-BEFORE.txt" || fail "Cannot identify recorder service"
IFS='|' read -r QNAME RUNMODE STATE RECORDER_PID < "$OUT/SERVICE-BEFORE.txt"
[ "$QNAME" = "$SERVICE" ] || fail "Unexpected service identity"
case "$RECORDER_PID" in ''|*[!0-9]*) fail "Invalid recorder PID" ;; esac
nemesis service stop "$SERVICE" || fail "Cannot stop recorder service"
# NEMESIS starts each service in its own session (setsid). Stop a possible
# logcat child too: older service stop kills just the shell, not its children.
if [ "$RECORDER_PID" -gt 1 ]; then
    kill -TERM "-$RECORDER_PID" 2>/dev/null || true
    sleep 1
    if kill -0 "-$RECORDER_PID" 2>/dev/null; then
        kill -KILL "-$RECORDER_PID" 2>/dev/null || true
        sleep 1
    fi
    kill -0 "-$RECORDER_PID" 2>/dev/null && fail "Recorder process group still present; export again after restart"
fi
for FILE in "$DEBUG"/flow.log* "$DEBUG/STATUS.txt"; do
    [ -f "$FILE" ] || continue
    NAME=${FILE##*/}
    copy_verified "$FILE" "$OUT/$NAME" || fail "USB copy verification failed for $NAME"
done
for NAME in BUILD AR_BUILD.json AR_INSTALL.json; do
    [ -f "$PKG/$NAME" ] || continue
    copy_verified "$PKG/$NAME" "$OUT/$NAME" || fail "Cannot export $NAME"
done
logcat -d -v brief -s EvergreenAR:W '*:S' > "$OUT/recent-logcat.txt" 2>&1 || echo "Recent logcat unavailable" >> "$OUT/recent-logcat.txt"
sync || fail "Cannot flush USB copies"
# Recheck all sources against their USB copies before deleting anything.
for FILE in "$DEBUG"/flow.log* "$DEBUG/STATUS.txt"; do
    [ -f "$FILE" ] || continue
    SOURCE_SUM=$(ar_digest "$FILE") || fail "Cannot re-read internal log"
    USB_SUM=$(ar_digest "$OUT/${FILE##*/}") || fail "Cannot re-read USB copy"
    [ "$SOURCE_SUM" = "$USB_SUM" ] || fail "Logs changed or USB copy became unreadable"
done
for FILE in "$DEBUG"/flow.log* "$DEBUG/STATUS.txt"; do
    [ -f "$FILE" ] || continue
    rm "$FILE" || { echo "USB export verified, but could not remove $FILE"; exit 1; }
done
rmdir "$DEBUG" 2>/dev/null || true
echo "USB copies checksum-verified. Persistent recorder disabled across reboot. Verified internal logs removed. AR settings unchanged. $(date)" > "$OUT/FINISHED.txt"
sync || { echo "USB copies verified and internal cleanup done, but final sync failed. Keep USB connected."; exit 1; }
echo "Export and cleanup complete. Logging stopped. You may now remove USB."
