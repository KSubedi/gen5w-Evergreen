#!/system/bin/sh
# Change only the experimental package's own opt-in/tuning files, never /system.
SOURCE=${EVERGREEN_AR_SOURCE_ROOT:-}
if [ -z "$SOURCE" ]; then
    export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
fi
read -r MODE < AR_MODE || exit 1
case "$MODE" in enable|disable|status|basic|advanced|diagnostics|trace-on|trace-off|tune|tune-reset|baseline|hotspot|carplay-ev-off|carplay-ev-on) ;; *) echo "Unknown AR control mode"; exit 1 ;; esac
PKG=$SOURCE/data/nemesis/apps/wireless.carplay.aa
MARKER=$PKG/ar-hud.enabled
# Hotspot channel debugging (notes/hotspot-channel-debugging.md): read-only, any build of this package.
# Never copies a network name, password or hardware address.
redact() {
    # key=value lines: keep the key, drop values that name or unlock the network or a device.
    while IFS= read -r _l; do
        case "$_l" in
            '#'*|'') continue ;;
            *ssid*|*SSID*|*pass*|*PASS*|*psk*|*PSK*|*key*|*KEY*|*bssid*|*BSSID*|*addr*|*ADDR*|*mac*|*MAC*|*serial*|*uuid*|*device_name*|*pin*)
                echo "${_l%%[=:]*}=<removed>" ;;
            *) echo "$_l" ;;
        esac
    done
}
# Configuration files: plain key=value lines only. Anything else (binary files such as
# softap.conf, which holds the network name and password, or free text) is dropped.
redact_conf() {
    while IFS= read -r _l; do
        case "$_l" in *=*) ;; *) continue ;; esac
        _k=${_l%%=*}; _k=${_k#"${_k%%[! 	]*}"}
        case "$_k" in ''|*[!A-Za-z0-9_.-]*) continue ;; esac
        echo "$_l" | redact
    done
}
nomac() {
    # Hardware addresses out of free text; without sed, lines that carry one are dropped.
    if command -v sed >/dev/null 2>&1; then
        sed -e 's/[0-9A-Fa-f][0-9A-Fa-f]:[0-9A-Fa-f][0-9A-Fa-f]:[0-9A-Fa-f][0-9A-Fa-f]:[0-9A-Fa-f][0-9A-Fa-f]:[0-9A-Fa-f][0-9A-Fa-f]:[0-9A-Fa-f][0-9A-Fa-f]/<mac>/g'
    else
        while IFS= read -r _l; do case "$_l" in *:??:??:??:??:*) ;; *) echo "$_l" ;; esac; done
    fi
}
if [ "$MODE" = hotspot ]; then
    OUT=HOTSPOT; mkdir -p "$OUT" || exit 1; F=$OUT/hotspot.txt
    # lastn <n>: the last n lines of stdin (the unit has no tail)
    lastn() {
        _t=$OUT/.lastn; cat > "$_t"; _c=0
        while IFS= read -r _l || [ -n "$_l" ]; do _c=$((_c + 1)); done < "$_t"
        _s=$((_c - $1)); _i=0
        while IFS= read -r _l || [ -n "$_l" ]; do _i=$((_i + 1)); [ "$_i" -gt "$_s" ] && echo "$_l"; done < "$_t"
        rm -f "$_t"
    }
    {
        echo "hotspot report $(date)"; echo "product $(getprop ro.build.product)"; echo "uptime $(cat /proc/uptime)"
        [ -f "$PKG/AR_INSTALL.json" ] && grep '"version"' "$PKG/AR_INSTALL.json"
        [ -f "$PKG/BUILD" ] && echo "build $(cat "$PKG/BUILD")"
        echo; echo "===== hostapd processes"
        CONFS=""
        for P in /proc/[0-9]*; do
            [ -r "$P/cmdline" ] || continue
            if command -v tr >/dev/null 2>&1; then CMD=$(tr '\000' ' ' < "$P/cmdline" 2>/dev/null); else CMD=$(cat "$P/cmdline" 2>/dev/null); fi
            case "$CMD" in *hostapd*|*wpa_supplicant*|*dnsmasq*)
                # Flags and file paths only: an argument could carry a network name.
                SAFE=""; for A in $CMD; do case "$A" in -*|/*) SAFE="$SAFE $A" ;; *) SAFE="$SAFE <arg>" ;; esac
                    case "$A" in *.conf) CONFS="$CONFS $A" ;; esac; done
                echo "${P#/proc/}:$SAFE" ;; esac
        done
        echo; echo "===== configuration (values that name or unlock the network removed)"
        for C in $CONFS "$SOURCE"/data/misc/wifi/hostapd.conf "$SOURCE"/data/misc/wifi/*.conf "$SOURCE"/system/etc/wifi/*.conf "$SOURCE"/system/etc/hostapd/*.conf; do
            [ -f "$C" ] || continue
            case " $DONE " in *" $C "*) continue ;; esac; DONE="$DONE $C"
            case "${C##*/}" in softap.conf) echo "--- $C (binary, holds the network name and password: not copied)"; continue ;; esac
            echo "--- $C"; redact_conf < "$C"
        done
        echo; echo "===== files"; ls -l "$SOURCE"/data/misc/wifi "$SOURCE"/system/etc/wifi 2>&1
        echo; echo "===== properties"; getprop | grep -i -e wifi -e wlan -e hostapd -e softap -e country -e channel -e ap_ | redact
        WL=""; for T in wl wlx dhdutil; do command -v "$T" >/dev/null 2>&1 && { WL=$T; break; }; done
        echo; echo "===== Wi-Fi chip (${WL:-no wl tool on this unit})"
        if [ -n "$WL" ]; then
            for Q in ver chanspec channel band bw_cap country autocountry chanim_mode chanim_stats chanim_sample_period \
                     obss_coex spect radar dfs_status interference interference_override mpc ap apsta mchan_algo; do
                echo "--- $WL $Q"; "$WL" -i wlan0 $Q 2>&1 | nomac
            done
            echo "--- associated phones: $("$WL" -i wlan0 assoclist 2>/dev/null | grep -c assoclist)"
        fi
        if command -v hostapd_cli >/dev/null 2>&1; then
            echo; echo "===== hostapd_cli status"; hostapd_cli -i wlan0 status 2>&1 | redact
        fi
        echo; echo "===== /proc/net/wireless"; cat /proc/net/wireless 2>&1
        echo; echo "===== kernel: channel switches, disconnects, access point, radar"
        dmesg 2>/dev/null | grep -e ch_switch -e WLC_E_ -e 'AP/GO' -e chanspec -e radar -e DFS -e CSA -e start_ap | nomac
        echo; echo "===== hostapd interference statistics and channel messages (last 400)"
        logcat -d -v time -s hostapd:V 2>/dev/null | grep -i -e chanspec -e channel -e csa -e acs -e interference | nomac | lastn 400
        echo; echo "===== projection: screen timeouts, session ends (last 200)"
        logcat -d -v time 2>/dev/null | grep -e onScreenTimeout -e onSessionFinalized -e onSessionStarted -e onNotifyDecodeFailure -e 'wifi kernel_s' | nomac | lastn 200
    } > "$F" 2>&1
    sync
    echo "Saved HOTSPOT/hotspot.txt to the USB (no network name, password or hardware address). Export complete."
    exit 0
fi
# Apple Maps EV routing over CarPlay: read at the iAP2 app's start (CarPlayEv), so after a
# head-unit restart. Off is allowed on any build; it only adds an empty flag file.
if [ "$MODE" = carplay-ev-off ]; then
    [ -d "$PKG" ] || { echo "The package is not installed."; exit 1; }
    : > "$PKG/carplay-ev.disabled" || exit 1
    chmod 644 "$PKG/carplay-ev.disabled" 2>/dev/null
    sync
    echo "Apple Maps EV routing over CarPlay turned off. Restart the head unit."
    exit 0
fi
if [ "$MODE" = carplay-ev-on ]; then
    rm -f "$PKG/carplay-ev.disabled" || exit 1
    sync
    echo "Apple Maps EV routing over CarPlay turned on (if this build has it). Restart the head unit."
    exit 0
fi
if [ "$MODE" = disable ]; then
    rm -f "$MARKER" || exit 1
    echo "CarPlay AR disabled. Start a fresh route to refresh the HUD."
    exit 0
fi
case "$(getprop ro.build.product)" in CVEV.USA.*.260507|CVEV.CAN.*.260206|NEEV.USA.*.260903|NEEV23.USA.*.260903|NEEV23SE.USA.*.260903) ;; *) echo "No AR build for this car (EV6 US 260507, EV6 Canada 260206, Ioniq 5 US 260903 so far)."; exit 1 ;; esac
if [ ! -f "$PKG/AR_BUILD.json" ] || ! grep -q -e '"variant": "ev6"' -e '"variant": "ev6_ca"' -e '"variant": "neev23_260903"' "$PKG/AR_BUILD.json"; then
    echo "The generated EV6 CarPlay AR prototype is not installed."
    exit 1
fi
DEBUG=$SOURCE/data/nemesis/wireless.carplay.aa-ar-debug
if [ "$MODE" = diagnostics ]; then
    OUT=AR-DEBUG
    mkdir -p "$OUT" || exit 1
    echo "AR export $(date)" > "$OUT/EXPORT.txt"
    for FILE in "$DEBUG"/flow.log* "$DEBUG/STATUS.txt" "$DEBUG"/cluster-baseline*.log; do
        [ -f "$FILE" ] && cp "$FILE" "$OUT/"
    done
    for NAME in BUILD AR_BUILD.json AR_INSTALL.json ar-tuning; do
        [ -f "$PKG/$NAME" ] && cp "$PKG/$NAME" "$OUT/"
    done
    getprop ro.build.product >> "$OUT/EXPORT.txt"
    if logcat -d -v brief -s EvergreenAR:W '*:S' > AR-DIAGNOSTICS.txt 2>&1; then
        cp AR-DIAGNOSTICS.txt "$OUT/recent-logcat.txt"
        echo "Recent logcat exported" >> "$OUT/EXPORT.txt"
    else
        echo "Recent logcat unavailable; persistent logs may still be present" >> "$OUT/EXPORT.txt"
    fi
    sync
    echo "Saved AR-DEBUG folder and AR-DIAGNOSTICS.txt to USB. Export complete."
    exit 0
elif [ "$MODE" = basic ]; then
    : > "$PKG/ar-advanced.disabled" || exit 1
    chmod 644 "$PKG/ar-advanced.disabled" || exit 1
    echo "Basic AR selected: interpolation and progress overlay disabled. Start a fresh route."
elif [ "$MODE" = advanced ]; then
    grep -q '"schema": 2' "$PKG/AR_BUILD.json" || { echo "Advanced AR adapters are not installed."; exit 1; }
    rm -f "$PKG/ar-advanced.disabled" || exit 1
    echo "Advanced AR selected. Start a fresh route."
elif [ "$MODE" = trace-on ]; then
    : > "$PKG/ar-trace.enabled" || exit 1
    chmod 644 "$PKG/ar-trace.enabled" || { rm -f "$PKG/ar-trace.enabled"; exit 1; }
    echo "Detailed EvergreenAR tracing enabled (takes effect within five seconds)."
elif [ "$MODE" = trace-off ]; then
    rm -f "$PKG/ar-trace.enabled" || exit 1
    echo "Detailed EvergreenAR tracing disabled. Once-per-second turn records and warnings remain."
elif [ "$MODE" = tune ]; then
    # key=value lines only; the helper rejects out-of-range values itself.
    [ -f AR_TUNING ] || { echo "Missing AR_TUNING on the USB."; exit 1; }
    [ "$(wc -c < AR_TUNING)" -le 4096 ] || { echo "AR_TUNING is too large."; exit 1; }
    while read -r LINE; do
        case "$LINE" in ''|'#'*) continue ;; esac
        KEY=${LINE%%=*}
        VALUE=${LINE#*=}
        case "$KEY" in street_ar_meters|gain|backstep_meters|bar_meters|turn_offset_meters|guidance_restart_s|street_ar_seconds) ;; exit_side|exit_style|recover|android_auto) ;; *) echo "Unknown tuning key: $KEY"; exit 1 ;; esac
        if [ "$KEY" = exit_side ]; then
            case "$VALUE" in auto|right|left|generic) ;; *) echo "Bad tuning value for $KEY"; exit 1 ;; esac
        elif [ "$KEY" = exit_style ]; then
            case "$VALUE" in slight|sign) ;; *) echo "Bad tuning value for $KEY"; exit 1 ;; esac
        elif [ "$KEY" = android_auto ]; then
            case "$VALUE" in on|off) ;; *) echo "Bad tuning value for $KEY"; exit 1 ;; esac
        elif [ "$KEY" = recover ]; then
            case "$VALUE" in off|keyframe|screen|reconnect) ;; *) echo "Bad tuning value for $KEY"; exit 1 ;; esac
        else
            case "$VALUE" in ''|*[!0-9.]*|*.*.*) echo "Bad tuning value for $KEY"; exit 1 ;; esac
        fi
    done < AR_TUNING
    cp AR_TUNING "$PKG/ar-tuning.new" && mv "$PKG/ar-tuning.new" "$PKG/ar-tuning" && chmod 644 "$PKG/ar-tuning" || { rm -f "$PKG/ar-tuning.new"; exit 1; }
    echo "AR tuning applied (takes effect within two seconds; start a fresh route):"
    cat "$PKG/ar-tuning"
elif [ "$MODE" = tune-reset ]; then
    rm -f "$PKG/ar-tuning" "$PKG/ar-tuning.new" || exit 1
    echo "AR tuning removed; built-in defaults apply within two seconds."
elif [ "$MODE" = baseline ]; then
    [ -f "$PKG/svc/ar-baseline.sh" ] || { echo "This build has no cluster baseline service (needs AR 1.2.0.106 or newer)."; exit 1; }
    MINUTES=20
    [ -f AR_BASELINE_MINUTES ] && read -r MINUTES < AR_BASELINE_MINUTES
    case "$MINUTES" in ''|*[!0-9]*) MINUTES=20 ;; esac
    [ "$MINUTES" -gt 60 ] && MINUTES=60
    [ "$MINUTES" -lt 1 ] && MINUTES=1
    echo "$MINUTES" > "$PKG/ar-baseline.requested.new" && mv "$PKG/ar-baseline.requested.new" "$PKG/ar-baseline.requested" || exit 1
    echo "Cluster baseline capture requested for $MINUTES minutes. Remove the USB, drive a route with the car's own navigation, then export with the diagnostics or finish kit."
elif [ "$MODE" = enable ]; then
    : > "$MARKER" || exit 1
    chmod 644 "$MARKER" || { rm -f "$MARKER"; exit 1; }
    echo "Experimental CarPlay AR enabled. Select AR mode in the car and start a fresh route."
elif [ -f "$MARKER" ]; then
    echo "Experimental CarPlay AR is enabled."
    if [ -f "$PKG/ar-advanced.disabled" ]; then
        echo "Advanced features are disabled (basic AR)."
    elif grep -q '"schema": 2' "$PKG/AR_BUILD.json"; then
        echo "Advanced AR features are selected."
    fi
    [ -f "$PKG/ar-trace.enabled" ] && echo "Detailed tracing is enabled."
    [ -f "$PKG/ar-tuning" ] && { echo "Road-test tuning:"; cat "$PKG/ar-tuning"; }
    [ -f "$PKG/ar-baseline.requested" ] && echo "A cluster baseline capture is pending."
else
    echo "Experimental CarPlay AR is disabled."
fi
