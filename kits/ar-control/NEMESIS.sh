#!/system/bin/sh
# Change only the experimental package's own opt-in/tuning files, never /system.
SOURCE=${EVERGREEN_AR_SOURCE_ROOT:-}
if [ -z "$SOURCE" ]; then
    export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
fi
read -r MODE < AR_MODE || exit 1
case "$MODE" in enable|disable|status|basic|advanced|diagnostics|trace-on|trace-off|tune|tune-reset|baseline) ;; *) echo "Unknown AR control mode"; exit 1 ;; esac
PKG=$SOURCE/data/nemesis/apps/wireless.carplay.aa
MARKER=$PKG/ar-hud.enabled
if [ "$MODE" = disable ]; then
    rm -f "$MARKER" || exit 1
    echo "CarPlay AR disabled. Start a fresh route to refresh the HUD."
    exit 0
fi
case "$(getprop ro.build.product)" in CVEV.USA.*.260507) ;; *) echo "This AR prototype targets the US EV6 on 260507."; exit 1 ;; esac
if [ ! -f "$PKG/AR_BUILD.json" ] || ! grep -q '"variant": "ev6"' "$PKG/AR_BUILD.json"; then
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
        case "$KEY" in street_ar_meters|gain|backstep_meters|bar_meters|turn_offset_meters) ;; exit_side|exit_style) ;; *) echo "Unknown tuning key: $KEY"; exit 1 ;; esac
        if [ "$KEY" = exit_side ]; then
            case "$VALUE" in auto|right|left|generic) ;; *) echo "Bad tuning value for $KEY"; exit 1 ;; esac
        elif [ "$KEY" = exit_style ]; then
            case "$VALUE" in slight|sign) ;; *) echo "Bad tuning value for $KEY"; exit 1 ;; esac
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
