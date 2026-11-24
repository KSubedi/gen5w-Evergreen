#!/system/bin/sh
# CarPlay Ultra-Lite test build: switch its parts on and off without reinstalling (src/carplay-ultra).
# The mode is the first word of ULTRA_MODE on this drive:
#   off       libCarPlayCore.so behaves as stock right away (no cpc-on), and stays off after a restart
#             (enabled=false). The theme stays in the identification (see notheme).
#   climate   right away: no Ultra virtual cluster, no uiSync, no extra /info feature bits (what
#             climate-only does); vehicle state protocol, panels and dock stay. Kept after a restart.
#   bare      climate, and also no instrument panel and no LIVI display extras: only the vehicle state
#             protocol description and the dock. Right away; until the head unit restarts.
#   on        everything back to the install's defaults: restart the head unit afterwards.
#   notheme   the theme id out of the CarPlay identification: restart the head unit, then forget the
#             car on the iPhone and pair again.
#   theme     the theme id back: restart, forget and pair again.
#   status    change nothing.
# One piece at a time, from everything on (run "on" and restart first, so the Java side runs with
# every listener; these take effect on the next connection and last until the head unit restarts):
#   full      every addition on, as installed
#   nofeat    full without the extra /info feature bits (cpc-feat)
#   featna    full with only the non-audio feature bits (upstream's livi_nonaudio, 205203a462)
#   noxfeat   full without enhancedRequestCarUI (cpc-xfeat)
#   nouisync  full without the theme layout channel (cpc-uisync)
#   nocluster full without the Ultra virtual cluster (cpc-ultra 0)
#   cluster1  full with the plain 800x480 virtual cluster instead of the theme's (cpc-ultra 1)
# Every run writes ULTRA-CONTROL-<date-time>.txt on this drive with what is set now.
# Only DiPO's cpc-* and uwp-carplay-ultra.conf files and our package's carplay-ultra.theme change;
# never /system.
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
DIPO=/data/data/com.daudio.app.dipo/files
PKG=/data/nemesis/apps/wireless.carplay.aa
CONF=$DIPO/uwp-carplay-ultra.conf
THEME=$PKG/carplay-ultra.theme
THEME_ID=44C9FA31EC:985:19.0
OUT=ULTRA-CONTROL-$(date +%Y%m%d-%H%M%S).txt
toast() { am broadcast -a com.hkmc.intent.action.TOAST -e com.hkmc.intent.extra.TOAST_MESSAGE "$1" --ez com.hkmc.intent.extra.TOAST_POPUPSHOWING true >/dev/null 2>&1; }
own() { chown 1000:1000 "$1" 2>/dev/null || chown 1000.1000 "$1" 2>/dev/null; chmod 600 "$1" 2>/dev/null; }
put() { echo "$2" > "$DIPO/$1" && own "$DIPO/$1"; }
conf() { : > "$CONF"; for l in "$@"; do echo "$l" >> "$CONF"; done; own "$CONF"; chmod 644 "$CONF"; }
MODE=
[ -f ULTRA_MODE ] && read -r MODE _rest < ULTRA_MODE
[ -d "$DIPO" ] && [ -f "$PKG/carplay-ultra.on" ] || { toast "CarPlay Ultra-Lite isn't installed"; echo "not installed" > "$OUT"; exit 1; }
case "$MODE" in
    off)     conf enabled=false; rm -f "$DIPO/cpc-on"; MSG="CarPlay Ultra-Lite off. Connect the phone now (no restart needed)." ;;
    climate) conf carplay_ultra=false; put cpc-ultra 0; put cpc-uisync 0; put cpc-xfeat 0; rm -f "$DIPO/cpc-feat"; put cpc-on ""
             MSG="Climate only: no Ultra cluster, uiSync or feature bits. Connect the phone now." ;;
    bare)    conf carplay_ultra=false; put cpc-ultra 0; put cpc-uisync 0; put cpc-xfeat 0; rm -f "$DIPO/cpc-feat"
             put cpc-nocluster 1; put cpc-extras 0; put cpc-on ""
             MSG="Bare: vehicle state protocol and dock only. Connect the phone now." ;;
    on)      rm -f "$CONF" "$DIPO/cpc-nocluster"; MSG="Defaults back: restart the head unit now." ;;
    notheme) rm -f "$THEME"; MSG="Theme out: restart the head unit, then forget the car on the iPhone and pair again." ;;
    theme)   echo "$THEME_ID" > "$THEME"; chmod 644 "$THEME"; MSG="Theme back: restart the head unit, then forget the car on the iPhone and pair again." ;;
    full|nofeat|featna|noxfeat|nouisync|nocluster|cluster1)
             put cpc-ultra 2; put cpc-uisync 1; put cpc-xfeat 1; put cpc-feat 205253ac62; put cpc-extras 1
             put cpc-version 1; put cpc-dock 1; rm -f "$DIPO/cpc-nocluster"
             case "$MODE" in
                 nofeat) rm -f "$DIPO/cpc-feat" ;;
                 featna) put cpc-feat 205203a462 ;;
                 noxfeat) put cpc-xfeat 0 ;;
                 nouisync) put cpc-uisync 0 ;;
                 nocluster) put cpc-ultra 0 ;;
                 cluster1) put cpc-ultra 1 ;;
             esac
             put cpc-on ""
             MSG="$MODE set. Connect the phone now (no restart needed)." ;;
    status)  MSG="Status saved on the drive." ;;
    *)       MSG="Unknown ULTRA_MODE word: see the top of NEMESIS.sh." ;;
esac
{
    echo "$(date) mode=${MODE:-none}"; echo "$MSG"
    echo "--- uwp-carplay-ultra.conf"; cat "$CONF" 2>&1
    echo "--- theme: $(cat "$THEME" 2>&1)"
    echo "--- DiPO cpc files"
    for f in "$DIPO"/cpc-*; do
        case "$f" in *.key|*'cpc-*') continue ;; esac
        v=; read -r v < "$f" 2>/dev/null; echo "${f##*/}=$v"
    done
} > "$OUT" 2>&1
sync
toast "$MSG"
echo "$MSG"
