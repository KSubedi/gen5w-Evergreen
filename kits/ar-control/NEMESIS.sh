#!/system/bin/sh
# Change only the experimental package's own opt-in file, never /system.
SOURCE=${EVERGREEN_AR_SOURCE_ROOT:-}
if [ -z "$SOURCE" ]; then
    export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
fi
read -r MODE < AR_MODE || exit 1
case "$MODE" in enable|disable|status|basic|advanced|diagnostics) ;; *) echo "Unknown AR control mode"; exit 1 ;; esac
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
if [ "$MODE" = diagnostics ]; then
    OUT=AR-DEBUG
    mkdir -p "$OUT" || exit 1
    DEBUG=$SOURCE/data/nemesis/wireless.carplay.aa-ar-debug
    echo "AR export $(date)" > "$OUT/EXPORT.txt"
    for FILE in "$DEBUG"/flow.log* "$DEBUG/STATUS.txt"; do
        [ -f "$FILE" ] && cp "$FILE" "$OUT/"
    done
    for NAME in BUILD AR_BUILD.json AR_INSTALL.json; do
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
else
    echo "Experimental CarPlay AR is disabled."
fi
