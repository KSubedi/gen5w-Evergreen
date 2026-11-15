#!/system/bin/sh
# This experimental installer is for one inspected firmware, never basic mode.
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
case "$(getprop ro.build.product)" in
    CVEV.USA.*.260507) ;;
    *) echo "AR install refused: this build requires a US EV6 on 260507."; exit 1 ;;
esac
[ -s AR_BUILD.json ] && [ -s AR_INSTALL.json ] && [ -f ar-hud.enabled ] || {
    echo "AR install refused: experimental package is incomplete."
    exit 1
}
exec /system/bin/sh scripts/pre.sh
