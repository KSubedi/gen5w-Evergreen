#!/system/bin/sh
# Dedicated, non-sensitive IPC: system-uid DiPO writes, navigation reads.
# Never make the package directory itself writable to apps.
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
STATE=/data/nemesis/apps/wireless.carplay.aa/ar-state
if mkdir -p "$STATE" && chmod 755 "$STATE" && {
    chown 1000.1000 "$STATE" 2>/dev/null || chown 1000:1000 "$STATE"
}; then
    rm -f "$STATE/progress" "$STATE/progress.tmp" "$STATE"/progress-*.tmp
else
    # Optional progress IPC must not block otherwise working wireless projection.
    # Without a writable producer/readable lease, the consumer retains OEM data.
    echo "WARN: AR progress state unavailable; exact-distance AR can still run."
    echo "WARN: AR progress state unavailable; retaining OEM bar." >> /data/nemesis/wireless.carplay.aa.log
fi
# A car without the factory AR HUD (the firmware's own flag, which DiPO and GPM read too,
# isn't "true"; e.g. a Sonata Hybrid with a regular HUD): AR stays off, and the helpers
# pass every turn frame to the cluster and HUD unchanged. postinstall runs in the package
# directory, where ArHudIpc looks for the opt-in.
if [ "$(getprop ro.product.opt.hud.artist)" != true ]; then
    rm -f ar-hud.enabled
    echo "No AR HUD on this car (ro.product.opt.hud.artist is not true): AR off, turn-by-turn as usual." \
        >> /data/nemesis/wireless.carplay.aa.log
fi
exec /system/bin/sh scripts/post.sh
