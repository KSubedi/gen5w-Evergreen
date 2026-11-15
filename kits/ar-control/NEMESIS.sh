#!/system/bin/sh
# Change only the experimental package's own opt-in file, never /system.
SOURCE=${EVERGREEN_AR_SOURCE_ROOT:-}
if [ -z "$SOURCE" ]; then
    export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
fi
read -r MODE < AR_MODE || exit 1
case "$MODE" in enable|disable|status) ;; *) echo "Unknown AR control mode"; exit 1 ;; esac
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
if [ "$MODE" = enable ]; then
    : > "$MARKER" || exit 1
    chmod 644 "$MARKER" || { rm -f "$MARKER"; exit 1; }
    echo "Experimental CarPlay AR enabled. Select AR mode in the car and start a fresh route."
elif [ -f "$MARKER" ]; then
    echo "Experimental CarPlay AR is enabled."
else
    echo "Experimental CarPlay AR is disabled."
fi
