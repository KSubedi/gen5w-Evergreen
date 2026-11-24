#!/system/bin/sh
# Ultimate Wireless Projection service wireless.carplay.aa/carplay-ultra: what the CarPlay
# Ultra-Lite test build (src/carplay-ultra) needs root for. Idle unless part 3_bridge installed
# the CarPlay Ultra-Lite build of DiPO (it writes carplay-ultra.on next to our package).
#
# - Firewall: CarPlay Ultra-Lite's vehicle state streams listen in DiPO on TCP 7130-7135 for the
#   iPhone on the hotspot (HMG-API opens them for it: mod.json "network"). One rule, in our own
#   chain UWP_ULTRA that INPUT jumps to first, checked every 10 s because the stock firewall or a
#   Wi-Fi restart can take it away. Only from the hotspot's addresses on wlan0. Uninstall
#   (3_bridge remove_ultra_files) takes the chain out.
# - CarPlay Ultra-Lite's own root helper (carplay-ultra-helper.sh, its svc/helper.sh unchanged):
#   the Light Vibes switch and Launchpad's preconditioning state for the Vehicle page.
# - DiPO's uwp-carplay-ultra.log copied to /data/nemesis/carplay.ultra.log for the USB kit.
# The service keeps running rather than exiting: NEMESIS stops restarting a service that exits
# five times in a row. Output: nemesis log service wireless.carplay.aa/carplay-ultra

export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin

PKG=/data/nemesis/apps/wireless.carplay.aa
ON=$PKG/carplay-ultra.on
DIPO_LOG=/data/data/com.daudio.app.dipo/files/uwp-carplay-ultra.log
OUT_LOG=/data/nemesis/carplay.ultra.log
CHAIN=UWP_ULTRA
POLL_S=${ULTRA_POLL_S:-10}
HELPER="${0%/*}/carplay-ultra-helper.sh"

log() {
    echo "$(date) $1"
}

if [ ! -f "$ON" ]; then
    log "CarPlay Ultra-Lite isn't installed (no $ON); idle"
    while :; do
        sleep 3600
        [ -f "$ON" ] && exit 0   # installed meanwhile: NEMESIS restarts us
    done
fi

# fw_ensure: our chain with its one rule, and INPUT jumping to it first
fw_ensure() {
    iptables -N $CHAIN 2>/dev/null
    if ! iptables -C $CHAIN -i wlan0 -s 192.168.0.0/24 -p tcp --dport 7130:7135 -j ACCEPT 2>/dev/null; then
        iptables -F $CHAIN 2>/dev/null
        iptables -A $CHAIN -i wlan0 -s 192.168.0.0/24 -p tcp --dport 7130:7135 -j ACCEPT 2>/dev/null \
            && log "firewall: TCP 7130-7135 open from the hotspot"
    fi
    if ! iptables -C INPUT -j $CHAIN 2>/dev/null; then
        iptables -I INPUT 1 -j $CHAIN 2>/dev/null && log "firewall: INPUT jumps to $CHAIN"
    fi
}

log "CarPlay Ultra-Lite service started"
if [ -f "$HELPER" ]; then
    sh "$HELPER" &
    HPID=$!
else
    log "no $HELPER: Vehicle page switches for Light Vibes and Launchpad stay off"
fi
last=
while :; do
    [ -f "$ON" ] || { log "CarPlay Ultra-Lite was removed; stopping"; [ -n "$HPID" ] && kill $HPID 2>/dev/null; while :; do sleep 3600; done; }
    fw_ensure
    if [ -f "$DIPO_LOG" ]; then
        l=$(ls -l "$DIPO_LOG" 2>/dev/null)
        if [ "$l" != "$last" ]; then
            cat "$DIPO_LOG" > "$OUT_LOG.tmp" 2>/dev/null && mv "$OUT_LOG.tmp" "$OUT_LOG"
            last=$l
        fi
    fi
    sleep $POLL_S
done
