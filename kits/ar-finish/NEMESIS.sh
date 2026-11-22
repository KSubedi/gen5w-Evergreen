#!/system/bin/sh
# Freeze only our recorder, verify USB copies, then remove only those verified logs.
SOURCE=${EVERGREEN_AR_SOURCE_ROOT:-}
if [ -z "$SOURCE" ]; then
    export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
fi
PKG=$SOURCE/data/nemesis/apps/wireless.carplay.aa
DEBUG=$SOURCE/data/nemesis/wireless.carplay.aa-ar-debug
OUT=AR-DEBUG-FINISHED
SNAP=AR-DEBUG-SNAPSHOT
SERVICE=wireless.carplay.aa/ar-debug
# NEMESIS shows only that the operation failed, so the reason is also left on the USB.
fail() {
    echo "ERROR: $1. Internal logs have not been deleted."
    {
        echo "ERROR: $1."
        echo "Internal logs have not been deleted. $(date)"
        [ -d "$SNAP" ] && echo "An unverified copy of the logs is in $SNAP. Share that folder together with this file."
    } > AR-EXPORT-ERROR.txt 2>/dev/null
    sync
    exit 1
}
# Plain copy taken before anything is checked or changed, so a failed run still leaves the logs on the USB.
snapshot() {
    mkdir -p "$SNAP" || return 1
    for FILE in "$DEBUG"/flow.log* "$DEBUG/STATUS.txt" "$DEBUG"/cluster-baseline*.log \
            "$PKG/BUILD" "$PKG/AR_BUILD.json" "$PKG/AR_INSTALL.json" "$PKG/ar-tuning"; do
        [ -f "$FILE" ] && cp "$FILE" "$SNAP/"
    done
    logcat -d -v brief -s EvergreenAR:W '*:S' > "$SNAP/recent-logcat.txt" 2>&1
    sync
}
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
rm -f AR-EXPORT-ERROR.txt
case "$(getprop ro.build.product)" in CVEV.USA.*.260507) ;; *) fail "Unsupported firmware" ;; esac
snapshot
[ -f "$PKG/AR_INSTALL.json" ] && grep -q -e '"version": "1.2.0.10[4-9]"' -e '"version": "1.2.0.11[0-2]"' -e '"version": "1.3.0.11[3-6]"' "$PKG/AR_INSTALL.json" || fail "This finish kit requires AR 1.2.0.104 to 1.3.0.116"
mkdir -p "$OUT" || fail "Cannot create USB export folder"
: > "$OUT/VERIFIED.txt" || fail "USB not writable"
[ -f original-md5.txt ] || fail "Missing recorder fingerprints"
INSTALLED=$(ar_digest "$PKG/svc/ar-debug.sh") || fail "Cannot checksum installed recorder"
STOPPED=$(ar_digest stopped-recorder.sh) || fail "Cannot checksum idle recorder"
KNOWN=0
while read -r ORIGINAL; do
    [ "$INSTALLED" = "$ORIGINAL" ] && KNOWN=1
done < original-md5.txt
[ "$KNOWN" = 1 ] || [ "$INSTALLED" = "$STOPPED" ] || fail "Recorder differs from the tested version"
copy_verified "$PKG/svc/ar-debug.sh" "$OUT/RECORDER-BEFORE.sh" || fail "Cannot back up recorder on USB"
# Only our own service script is changed; this prevents log capture returning at reboot.
cp stopped-recorder.sh "$PKG/svc/ar-debug.sh.new" && chmod 755 "$PKG/svc/ar-debug.sh.new" || fail "Cannot stage idle recorder"
[ "$(ar_digest "$PKG/svc/ar-debug.sh.new")" = "$STOPPED" ] || fail "Idle recorder copy mismatch"
mv "$PKG/svc/ar-debug.sh.new" "$PKG/svc/ar-debug.sh" || fail "Cannot disable recorder persistently"
sync || fail "Cannot flush recorder disable change"
# The PID is only used to stop the recorder's own process group. Without a
# readable status the service is still stopped by name; a recorder that keeps
# writing then fails the checksum comparison below and nothing is deleted.
QNAME=; RECORDER_PID=
nemesis service status "$SERVICE" > "$OUT/SERVICE-BEFORE.txt" 2>/dev/null &&
    IFS='|' read -r QNAME RUNMODE STATE RECORDER_PID < "$OUT/SERVICE-BEFORE.txt"
[ "$QNAME" = "$SERVICE" ] || RECORDER_PID=
case "$RECORDER_PID" in ''|*[!0-9]*) RECORDER_PID=0; echo "Recorder PID unknown; service stopped by name only" >> "$OUT/SERVICE-BEFORE.txt" ;; esac
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
for FILE in "$DEBUG"/flow.log* "$DEBUG/STATUS.txt" "$DEBUG"/cluster-baseline*.log; do
    [ -f "$FILE" ] || continue
    NAME=${FILE##*/}
    copy_verified "$FILE" "$OUT/$NAME" || fail "USB copy verification failed for $NAME"
done
for NAME in BUILD AR_BUILD.json AR_INSTALL.json ar-tuning; do
    [ -f "$PKG/$NAME" ] || continue
    copy_verified "$PKG/$NAME" "$OUT/$NAME" || fail "Cannot export $NAME"
done
logcat -d -v brief -s EvergreenAR:W '*:S' > "$OUT/recent-logcat.txt" 2>&1 || echo "Recent logcat unavailable" >> "$OUT/recent-logcat.txt"
sync || fail "Cannot flush USB copies"
# Recheck all sources against their USB copies before deleting anything.
for FILE in "$DEBUG"/flow.log* "$DEBUG/STATUS.txt" "$DEBUG"/cluster-baseline*.log; do
    [ -f "$FILE" ] || continue
    SOURCE_SUM=$(ar_digest "$FILE") || fail "Cannot re-read internal log"
    USB_SUM=$(ar_digest "$OUT/${FILE##*/}") || fail "Cannot re-read USB copy"
    [ "$SOURCE_SUM" = "$USB_SUM" ] || fail "Logs changed or USB copy became unreadable"
done
for FILE in "$DEBUG"/flow.log* "$DEBUG/STATUS.txt" "$DEBUG"/cluster-baseline*.log; do
    [ -f "$FILE" ] || continue
    rm "$FILE" || { echo "USB export verified, but could not remove $FILE"; exit 1; }
done
rmdir "$DEBUG" 2>/dev/null || true
# The verified export replaces the early plain copy.
rm -f "$SNAP"/* 2>/dev/null
rmdir "$SNAP" 2>/dev/null || true
echo "USB copies checksum-verified. Persistent recorder disabled across reboot. Verified internal logs removed. AR settings unchanged. $(date)" > "$OUT/FINISHED.txt"
sync || { echo "USB copies verified and internal cleanup done, but final sync failed. Keep USB connected."; exit 1; }
echo "Export and cleanup complete. Logging stopped. You may now remove USB."
