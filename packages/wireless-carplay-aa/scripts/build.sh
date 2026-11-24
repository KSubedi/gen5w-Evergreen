#!/system/bin/sh
# Rebuild the modified projection apps on the head unit from its own stock files.
# The package carries only patch files (patches/<car>/*.lpd). For each app,
# `lpatch delta` rebuilds the modified code from the stock .odex - only if that
# .odex is the exact expected original - and `lpatch zipadd` adds it to the stock
# APK. Every result must match the md5 in patches/<car>/manifest.
# Output goes to $BUILD_DIR/<part>/, where the parts pick it up.
#
# EV features: a manifest line whose part ends in "+ev" (e.g. "2_tbt+ev app
# HKMC_GPM neev/HKMC_GPM.ev.lpd <md5>") is the EV build of that file (turn-by-turn
# plus egmp-aa-ev-feat's EV routing, preconditioning and logging). It replaces the
# plain line for the same part and file, unless an empty UWP-NO-EV (or EVERGREEN-NO-EV) file sits
# next to our NEMESIS.data on the install drive. A car with no +ev lines builds as
# before. $BUILD_DIR/ev records which way it went (1 or 0) for the install log.
#
# CarPlay Ultra-Lite (test build, src/carplay-ultra): "3_bridge+ultra" lines are the
# CarPlay Ultra-Lite builds of DiPO and libCarPlayCore.so. They are built only when a
# file named UWP-CARPLAY-ULTRA sits next to our NEMESIS.data on the install drive, and
# replace the plain DiPO line. If one doesn't fit this unit, the install goes on without
# CarPlay Ultra-Lite. A file whose text holds "climate-only" keeps the classic CarPlay
# look (no theme). $BUILD_DIR/ultra records 1 or 0, $BUILD_DIR/ultra-mode full|climate.
BUILD_DIR=/data/nemesis/build/wireless.carplay.aa
TBT_BACKUP=/data/nemesis/backup/projection.tbt
MENU_BACKUP=/data/nemesis/backup/projection.menu
LP_ROOT=.
. ./scripts/lpatch.sh

CARS_DIR=patches
. ./scripts/cars.sh
. ./scripts/universal.sh

lp_md5() {
    local _l
    _l=$("$(lp_bin)" md5 "$1" 2>/dev/null) || return 1
    set -- $_l
    echo "$1"
}

# the stock copy of a system file: a part's snapshot when it exists (the live
# file may already be modified), otherwise the live file. libCarPlayCore.so: part
# 3_bridge's snapshot is the stock library (it holds the bridge builds of the apps,
# so it is only used for the library).
BRIDGE_BACKUP=/data/nemesis/backup/wireless.carplay.bt
stock_src() {
    local _b _bridge=
    [ "${1##*/}" = libCarPlayCore.so ] && _bridge="$BRIDGE_BACKUP/libCarPlayCore.so"
    for _b in "$TBT_BACKUP/${1##*/}" "$MENU_BACKUP/${1##*/}" $_bridge; do
        [ -s "$_b" ] && { echo "$_b"; return; }
    done
    echo "$1"
}

# build_one <part> <kind> <name> <patch> <md5> [optional]: rebuild one file into
# $BUILD_DIR/<part>/ (an optional one logs a mismatch as a skip, not a failure)
build_one() {
    local _part="$1" _kind="$2" _name="$3" _patch="$4" _want="$5" _out _src _odex _tmp _f="FAIL:"
    [ "$6" = optional ] && _f="skip (optional):"
    _tmp="$BUILD_DIR/classes.dex"
    mkdir -p "$BUILD_DIR/$_part" || return 1
    case "$_kind" in
        app) _out="$BUILD_DIR/$_part/$_name.apk" ;;
        file) _out="$BUILD_DIR/$_part/$_name" ;;
        *) log "FAIL: bad manifest line ($_part $_kind)"; return 1 ;;
    esac
    if [ -s "$_out" ] && [ "$(lp_md5 "$_out")" = "$_want" ]; then
        return 0
    fi
    rm -f "$_out" "$_tmp" 2>/dev/null
    if [ "$_kind" = app ]; then
        _src=$(stock_src "/system/app/$_name.apk")
        _odex=$(stock_src "/system/app/$_name.odex")
        if ! "$(lp_bin)" delta "patches/$_patch" "$_odex" "$_tmp" >> "$LOG" 2>&1; then
            log "$_f $_odex is not the stock file this patch needs"
            return 1
        fi
        if ! "$(lp_bin)" zipadd "$_src" "$_tmp" "$_out" >> "$LOG" 2>&1; then
            rm -f "$_tmp"
            log "$_f could not assemble $_name.apk"
            return 1
        fi
        rm -f "$_tmp"
    else
        _src=$(stock_src "/system/lib/$_name")
        if ! "$(lp_bin)" delta "patches/$_patch" "$_src" "$_out" >> "$LOG" 2>&1; then
            log "$_f $_src is not the stock file this patch needs"
            return 1
        fi
    fi
    if [ "$(lp_md5 "$_out")" != "$_want" ]; then
        rm -f "$_out"
        log "$_f rebuilt $_name does not match the expected result"
        return 1
    fi
    log "built $_part/${_out##*/} from the stock files"
    return 0
}

# EV builds wanted? Yes unless UWP-NO-EV (or the older EVERGREEN-NO-EV) is on the install drive.
ev_wanted() {
    local _d
    for _d in /storage/usb0 /storage/usb1 /storage/usb2 /mnt/media_rw/usb0 /mnt/media_rw/usb1 /mnt/media_rw/usb2; do
        [ -f "$_d/UWP-NO-EV" ] || [ -f "$_d/EVERGREEN-NO-EV" ] || continue
        grep -q 'wireless\.carplay\.aa\.zip' "$_d/NEMESIS.data" 2>/dev/null && return 1
    done
    return 0
}

# has_ev_line <manifest> <part> <name> [suffix]: the manifest has an EV (or <suffix>) build of that file
has_ev_line() {
    local _p _k _n _x _s
    while read -r _p _k _n _x _s; do
        [ "$_p" = "$2+${4:-ev}" ] && [ "$_n" = "$3" ] && return 0
    done < "$1"
    return 1
}

# the install drive's UWP-CARPLAY-ULTRA file (next to our NEMESIS.data), or nothing
ultra_marker() {
    local _d
    for _d in /storage/usb0 /storage/usb1 /storage/usb2 /mnt/media_rw/usb0 /mnt/media_rw/usb1 /mnt/media_rw/usb2; do
        [ -f "$_d/UWP-CARPLAY-ULTRA" ] || continue
        grep -q 'wireless\.carplay\.aa\.zip' "$_d/NEMESIS.data" 2>/dev/null && { echo "$_d/UWP-CARPLAY-ULTRA"; return 0; }
    done
    return 1
}

# build_ultra <manifest>: every "+ultra" line, or none (a mismatch removes what was built)
build_ultra() {
    local _part _kind _name _patch _want _n=0
    while read -r _part _kind _name _patch _want; do
        case "$_part" in *+ultra) ;; *) continue ;; esac
        _part=${_part%+ultra}
        if ! build_one "$_part" "$_kind" "$_name" "$_patch" "$_want" optional; then
            log "CarPlay Ultra-Lite: $_name doesn't fit this unit - installing without it"
            while read -r _part _kind _name _patch _want; do
                case "$_part" in *+ultra) rm -f "$BUILD_DIR/${_part%+ultra}/$_name.apk" "$BUILD_DIR/${_part%+ultra}/$_name" 2>/dev/null ;; esac
            done < "$1"
            return 1
        fi
        _n=$((_n + 1))
    done < "$1"
    log "CarPlay Ultra-Lite (test build): $_n file(s) built"
    return 0
}

build_payloads() {
    local _v _m _part _kind _name _patch _want _ev=0 _evn=0 _ultra=0 _marker
    _v=$(car_variant) || { log "FAIL: no patch set for this car"; return 1; }
    if [ "$_v" = auto ]; then   # the universal build (scripts/universal.sh)
        universal_rebuilt || universal_build || return 1
        build_optional
        return 0
    fi
    _m="patches/$_v/manifest"
    if [ ! -s "$_m" ]; then
        log "FAIL: missing $_m"
        return 1
    fi
    mkdir -p "$BUILD_DIR/2_tbt" "$BUILD_DIR/3_bridge" || return 1
    if grep -q '^[^ ]*+ev ' "$_m"; then
        if ev_wanted; then
            _ev=1
        else
            log "UWP-NO-EV on the install drive: turn-by-turn build without the EV features"
        fi
    fi
    echo full > "$BUILD_DIR/ultra-mode"
    if _marker=$(ultra_marker); then
        if ! grep -q '^[^ ]*+ultra ' "$_m"; then
            log "UWP-CARPLAY-ULTRA on the install drive, but this car has no CarPlay Ultra-Lite build yet"
        elif build_ultra "$_m"; then
            _ultra=1
            if grep -q 'climate-only' "$_marker" 2>/dev/null; then
                echo climate > "$BUILD_DIR/ultra-mode"
                log "CarPlay Ultra-Lite: climate only, classic CarPlay look (climate-only in UWP-CARPLAY-ULTRA)"
            fi
        fi
    fi
    while read -r _part _kind _name _patch _want; do
        [ -n "$_part" ] || continue
        case "$_part" in
            *+ultra) continue ;;   # build_ultra did them
            *+ev)
                [ $_ev = 1 ] || continue
                _part=${_part%+ev}
                _evn=$((_evn + 1))
                ;;
            *)
                [ $_ev = 1 ] && has_ev_line "$_m" "$_part" "$_name" && continue
                [ $_ultra = 1 ] && has_ev_line "$_m" "$_part" "$_name" ultra && continue
                ;;
        esac
        build_one "$_part" "$_kind" "$_name" "$_patch" "$_want" || return 1
    done < "$_m"
    echo $_ev > "$BUILD_DIR/ev"
    echo $_ultra > "$BUILD_DIR/ultra"
    [ $_ultra = 1 ] || rm -f "$BUILD_DIR/3_bridge/libCarPlayCore.so" 2>/dev/null
    # a set without a library line (the car's Android Auto library has no patch yet):
    # part 2 leaves the library stock, so only Android Auto turn-by-turn on the cluster is missing
    rm -f "$BUILD_DIR/2_tbt/no-lib" 2>/dev/null
    if ! grep -q '^2_tbt[^ ]* file libautoreceiver_infobank.so ' "$_m"; then
        : > "$BUILD_DIR/2_tbt/no-lib" || return 1
        log "no Android Auto library patch for this car yet: the library stays stock (Android Auto turn-by-turn on the cluster isn't in this build)"
    fi
    [ $_ev = 1 ] && log "EV features: $_evn file(s) built with EV routing, preconditioning and logging"
    build_optional
    return 0
}

clean_build() {
    case "$BUILD_DIR" in
        /data/nemesis/build/wireless.carplay.aa) rm -rf "$BUILD_DIR"; rmdir /data/nemesis/build 2>/dev/null ;;
    esac
}

# build_optional: the fixes in patches/common/optional, for units whose stock file
# matches. A file can have one line per stock version: the first that fits is built
# and the rest are passed over. Anything that fits no line is skipped, and its part
# has nothing to install.
build_optional() {
    local _part _kind _name _patch _want _done=" "
    [ -s patches/common/optional ] || return 0
    while read -r _part _kind _name _patch _want; do
        case "$_part" in ''|\#*) continue ;; esac
        case "$_done" in *" $_part/$_name "*) continue ;; esac
        if build_one "$_part" "$_kind" "$_name" "$_patch" "$_want" optional; then
            _done="$_done$_part/$_name "
        else
            rm -f "$BUILD_DIR/$_part/$_name.apk" "$BUILD_DIR/$_part/$_name" 2>/dev/null
        fi
    done < patches/common/optional
    return 0
}
