#!/system/bin/sh
# CarPlay Ultra-Lite test build: one-shot, read-only diagnostics (src/carplay-ultra). Run it right
# after CarPlay failed to connect, before restarting the head unit (the logs are in memory).
# Writes ULTRA-DIAG-<date-time>/ on this drive. Private: the logcat holds phone names and
# Bluetooth addresses. Changes nothing on the head unit. Session keys (cpc-vs-*.key) are not copied.
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
OUT=ULTRA-DIAG-$(date +%Y%m%d-%H%M%S)
mkdir -p "$OUT" || exit 1
DIPO=/data/data/com.daudio.app.dipo/files
PKG=/data/nemesis/apps/wireless.carplay.aa
toast() { am broadcast -a com.hkmc.intent.action.TOAST -e com.hkmc.intent.extra.TOAST_MESSAGE "$1" --ez com.hkmc.intent.extra.TOAST_POPUPSHOWING true >/dev/null 2>&1; }
copy() { [ -f "$1" ] && cat "$1" > "$2" 2>/dev/null; }
toast "CarPlay Ultra-Lite diagnostics: collecting, about 20 seconds"
{
    echo "date $(date)"; echo "uptime $(cat /proc/uptime)"
    echo "product $(getprop ro.build.product)"
    echo "package BUILD: $(cat $PKG/BUILD 2>/dev/null)"; grep '^package-version' $PKG/NEMESIS.data 2>/dev/null
    for f in /system/lib/libCarPlayCore.so /system/app/HKMC_DiPO.apk /system/app/Iap2ServerApp.apk /system/lib/libiap2session.so; do
        echo "$(md5 $f 2>/dev/null)"
    done
    echo "carplay-ultra.on: $(ls -l $PKG/carplay-ultra.on 2>&1)"
    echo "carplay-ultra.theme: $(cat $PKG/carplay-ultra.theme 2>&1)"
    echo "props: iap2.wifi=$(getprop persist.sys.iap2.wifi) iden.wireless=$(getprop persist.iap2.iden.wireless)"
    echo "--- DiPO files"; ls -la $DIPO 2>&1
    for f in $DIPO/cpc-*; do
        case "$f" in *.key|*'cpc-*') continue ;; esac
        echo "$f: $(cat "$f" 2>/dev/null | head -c 200)"
    done
    echo "--- services"; nemesis service status wireless.carplay.aa/carplay-ultra 2>&1
    echo "--- processes"; ps 2>&1 | grep -e dipo -e iap2 -e carplay -e hostapd
    echo "--- tombstones / anr"; ls -l /data/tombstones /data/anr 2>&1
} > "$OUT/summary.txt" 2>&1
copy $DIPO/uwp-carplay-ultra.log "$OUT/uwp-carplay-ultra.log"
copy $DIPO/uwp-carplay-ultra.log.1 "$OUT/uwp-carplay-ultra.log.1"
copy $DIPO/uwp-carplay-ultra.conf "$OUT/uwp-carplay-ultra.conf"
copy $DIPO/cpc-airplay "$OUT/cpc-airplay.txt"
copy /data/nemesis/carplay.ultra.log "$OUT/carplay.ultra.log"
{
    echo "--- iptables -S"; iptables -S 2>&1
    echo "--- iptables -L INPUT -n -v"; iptables -L INPUT -n -v 2>&1
    echo "--- iptables -L UWP_ULTRA -n -v"; iptables -L UWP_ULTRA -n -v 2>&1
    echo "--- iptables -t nat -S"; iptables -t nat -S 2>&1
    echo "--- netstat -an"; netstat -an 2>&1
    echo "--- ip addr"; ip addr 2>&1 | grep -v -e link/ether
} > "$OUT/network.txt" 2>&1
nemesis log service wireless.carplay.aa/carplay-ultra > "$OUT/service-carplay-ultra.txt" 2>&1
logcat -d -v threadtime -s TccCarPlay:V CarPlay_:V CarPlayUltra:V CarPlayEV:V DiPOManager:V AndroidRuntime:V DEBUG:V libc:V > "$OUT/logcat-carplay.txt" 2>&1
logcat -d -v threadtime > "$OUT/logcat-full.txt" 2>&1
logcat -d -v threadtime -b events > "$OUT/logcat-events.txt" 2>&1
dmesg > "$OUT/dmesg.txt" 2>&1
sync
toast "CarPlay Ultra-Lite diagnostics saved in $OUT. You can remove the drive."
echo "saved $OUT"
