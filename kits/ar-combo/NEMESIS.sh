#!/system/bin/sh
# One drive for the EV6 AR build. Parked, with Android running:
#   - this version not installed (or EVERGREEN-INSTALL on the drive): install it (the head
#     unit restarts by itself at the end);
#   - this version installed: export the AR logs (checksum-verified), clear them, keep
#     recording for the next drive, write the hotspot report and let the EV logs copy.
# While Android is still starting (NEMESIS confirms a drive by itself then, for example
# right after the install's restart) it does nothing.
SOURCE=${EVERGREEN_AR_SOURCE_ROOT:-}
if [ -z "$SOURCE" ]; then
    export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
fi
ID=wireless.carplay.aa
PKG=$SOURCE/data/nemesis/apps/$ID
DEBUG=$SOURCE/data/nemesis/$ID-ar-debug
SERVICE=$ID/ar-debug
ZIP=packages/$ID.zip
STATUS=EVERGREEN-STATUS.txt
# The package's install logs: its own and one per part (steps, checksums and settings
# values; no names or passwords).
NDATA=$SOURCE/data/nemesis
INSTALL_LOG=$NDATA/$ID.log
PART_LOGS="$ID wireless.projection projection.tbt wireless.carplay.bt carplay.wifi.audio projection.menu"
# The parts the installed version applied; none after a failed install that was undone.
PARTS_FILE=$NDATA/$ID.parts
read -r OURS < AR_VERSION 2>/dev/null || OURS=

toast() {
    am broadcast -a com.hkmc.intent.action.TOAST -e com.hkmc.intent.extra.TOAST_MESSAGE "$1" \
        --ez com.hkmc.intent.extra.TOAST_POPUPSHOWING true >/dev/null 2>&1 || true
}
status() {
    { echo "$1"; echo "$(date)"; } > "$STATUS" 2>/dev/null
    sync
}
fail() {
    echo "ERROR: $1"
    status "ERROR: $1. Nothing on the head unit was deleted. Unplug the drive and plug it in again to retry."
    toast "Evergreen: $1"
    exit 1
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
    echo "$_copy  ${2##*/}" >> "$OUT/VERIFIED.txt"
}

# save_install_logs <folder>: everything needed to see why an install failed.
save_install_logs() {
    mkdir -p "$1" 2>/dev/null || return 0
    for _n in $PART_LOGS; do
        [ -f "$NDATA/$_n.log" ] && cp "$NDATA/$_n.log" "$1/$_n.log"
    done
    { echo "parts installed: $(cat "$PARTS_FILE" 2>/dev/null || echo none)"
      echo "projection snapshot:"; ls -l "$NDATA/backup/wireless.projection" 2>&1
      echo "space:"; df /system /data 2>&1
      echo "/system mount:"; grep ' /system ' /proc/mounts 2>&1
    } > "$1/state.txt" 2>/dev/null
    # The unit has no tail; NEMESIS already keeps the dump to its last 128 KiB.
    nemesis log dump > "$1/nemesis.log" 2>/dev/null
    sync
}

if [ "$(getprop sys.boot_completed)" != 1 ]; then
    echo "Android is still starting: nothing to do. Plug the drive in again once the head unit is up."
    exit 0
fi
case "$(getprop ro.build.product)" in CVEV.USA.*.260507) ;; *) fail "This drive is for the Kia EV6 (US) on 260507 only" ;; esac
[ -n "$OURS" ] && [ -f "$ZIP" ] || fail "The drive is incomplete: unzip the whole release onto it again"

if [ ! -f EVERGREEN-INSTALL ] && [ -f "$PKG/AR_INSTALL.json" ] && grep -q "\"version\": \"$OURS\"" "$PKG/AR_INSTALL.json"; then
    MODE=logs
else
    MODE=install
fi

if [ "$MODE" = install ]; then
    # An earlier build's logs are copied first, so an update never loses a drive's worth.
    if ls "$DEBUG"/flow.log* >/dev/null 2>&1; then
        KEEP=AR-LOGS-BEFORE-UPDATE-$(date +%Y%m%d-%H%M%S)
        mkdir -p "$KEEP" && for FILE in "$DEBUG"/flow.log* "$DEBUG/STATUS.txt" "$PKG/AR_INSTALL.json"; do
            [ -f "$FILE" ] && cp "$FILE" "$KEEP/"
        done
    fi
    UPDATE=
    nemesis pkg-list 2>/dev/null | grep -q "^$ID|" && UPDATE=--update
    status "Installing Evergreen EV6 AR $OURS. The head unit restarts by itself when it's done: leave it on until it has.
Next time you plug this drive in (parked, head unit fully started), it collects the logs instead."
    toast "Evergreen: installing $OURS. The head unit restarts when it's done."
    rm -f EVERGREEN-INSTALL
    sync
    # NEMESIS's own install, as for a package drive. On success the package restarts the unit.
    nemesis install $UPDATE "$ZIP"
    RC=$?
    # 5: the old version's uninstall step failed, so NEMESIS kept it. When that version
    # has no parts installed (its own install failed and was undone), there is nothing to
    # remove: --force only carries on past that step (NEMESIS 1.16; older ones ignore it).
    if [ "$RC" = 5 ] && [ -n "$UPDATE" ] && [ ! -s "$PARTS_FILE" ]; then
        save_install_logs INSTALL-LOG-FIRST-TRY
        echo "The old version has no parts installed: retrying past its failed uninstall"
        nemesis install --update --force "$ZIP"
        RC=$?
    fi
    case "$RC" in
        0) status "Installed Evergreen EV6 AR $OURS. Restart the head unit if it didn't restart by itself.
Next time you plug this drive in, it collects the logs."; exit 0 ;;
        20) fail "NEMESIS needs you to accept a compatibility risk: install packages/$ID.zip from NEMESIS Manager instead" ;;
        21|22|23) fail "NEMESIS blocked the install (another package depends on or conflicts with it); see NEMESIS Manager" ;;
        *) save_install_logs INSTALL-LOG
           fail "Install failed (NEMESIS code $RC). Restart the head unit, then send the INSTALL-LOG folder from this drive" ;;
    esac
fi

# Log mode.
OUT=AR-LOGS-$(date +%Y%m%d-%H%M%S)
mkdir -p "$OUT" || fail "Cannot write to the drive"
: > "$OUT/VERIFIED.txt" || fail "Cannot write to the drive"
toast "Evergreen: collecting logs. Keep the drive in until it says done."
# Stop the recorder only while its files are copied; it starts again afterwards.
QNAME=; RECORDER_PID=
nemesis service status "$SERVICE" > "$OUT/SERVICE-BEFORE.txt" 2>/dev/null &&
    IFS='|' read -r QNAME RUNMODE STATE RECORDER_PID < "$OUT/SERVICE-BEFORE.txt"
[ "$QNAME" = "$SERVICE" ] || RECORDER_PID=
case "$RECORDER_PID" in ''|*[!0-9]*) RECORDER_PID=0 ;; esac
nemesis service stop "$SERVICE" >/dev/null 2>&1
if [ "$RECORDER_PID" -gt 1 ]; then
    kill -TERM "-$RECORDER_PID" 2>/dev/null || true
    sleep 1
    kill -0 "-$RECORDER_PID" 2>/dev/null && { kill -KILL "-$RECORDER_PID" 2>/dev/null || true; sleep 1; }
fi
restart_recorder() { [ -f "$PKG/svc/ar-debug.sh" ] && nemesis service start "$SERVICE" >/dev/null 2>&1; }
for FILE in "$DEBUG"/flow.log* "$DEBUG/STATUS.txt" "$DEBUG"/cluster-baseline*.log; do
    [ -f "$FILE" ] || continue
    copy_verified "$FILE" "$OUT/${FILE##*/}" || { restart_recorder; fail "Copying ${FILE##*/} to the drive could not be verified"; }
done
for NAME in BUILD AR_BUILD.json AR_INSTALL.json ar-tuning; do
    [ -f "$PKG/$NAME" ] && cp "$PKG/$NAME" "$OUT/"
done
[ -f "$INSTALL_LOG" ] && save_install_logs "$OUT/install-logs"
logcat -d -v brief -s EvergreenAR:W '*:S' > "$OUT/recent-logcat.txt" 2>&1 || echo "Recent logcat unavailable" >> "$OUT/recent-logcat.txt"
sync || { restart_recorder; fail "Cannot flush the drive"; }
# Recheck every source against its copy before anything is deleted.
for FILE in "$DEBUG"/flow.log* "$DEBUG/STATUS.txt" "$DEBUG"/cluster-baseline*.log; do
    [ -f "$FILE" ] || continue
    [ "$(ar_digest "$FILE")" = "$(ar_digest "$OUT/${FILE##*/}")" ] || { restart_recorder; fail "A log changed or its copy became unreadable"; }
done
for FILE in "$DEBUG"/flow.log* "$DEBUG"/cluster-baseline*.log; do
    [ -f "$FILE" ] && rm -f "$FILE"
done
restart_recorder || echo "The recorder service could not be started again" >> "$OUT/VERIFIED.txt"
# The hotspot's channel settings and history (read-only, no names, passwords or addresses).
if [ -f hotspot.sh ]; then
    echo hotspot > AR_MODE && sh hotspot.sh > "$OUT/hotspot-run.txt" 2>&1
    rm -f AR_MODE
    [ -f HOTSPOT/hotspot.txt ] && mv HOTSPOT/hotspot.txt "$OUT/hotspot.txt" && rmdir HOTSPOT 2>/dev/null
fi
# The EV features copy their own logs to any drive with this folder, within about 20 s.
mkdir -p egmp-aa-ev-logs 2>/dev/null
echo "Logs exported and verified. Recording continues for the next drive. $(date)" > "$OUT/FINISHED.txt"
sync
status "Logs saved in $OUT (send that folder privately). EV logs copy into egmp-aa-ev-logs within about 20 seconds: wait before unplugging.
Recording continues; plug this drive in again after the next drive. To reinstall this version, put an empty file named EVERGREEN-INSTALL on the drive."
toast "Evergreen: logs saved to the drive ($OUT). Wait 20 s for the EV logs, then unplug."
echo "Logs saved in $OUT. Export complete."
