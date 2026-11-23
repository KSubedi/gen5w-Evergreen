#!/system/bin/sh
# Stock-navigation cluster baseline. Idle until the control kit requests a capture;
# then records what the cluster service last received (turn type, direction,
# distance, bar, trap frame) once a second for the requested minutes, to the same
# private debug directory the diagnostics/finish kits export. Only lines about the
# turn and trap frames are kept; the turn list's road names are not.
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
umask 077
PKG=/data/nemesis/apps/wireless.carplay.aa
DIR=/data/nemesis/wireless.carplay.aa-ar-debug
REQUEST=$PKG/ar-baseline.requested
LIMIT=4194304
keep() {
    while read -r LINE; do
        case "$LINE" in
            *[Tt][Bb][Tt]*|*[Tt]urn*|*[Tt]rap*|*[Bb]ar*|*[Ss]peed*|*[Dd]ist*|*[Dd]irection*|*[Tt]ype*|*[Ll]imit*) echo "$LINE" ;;
        esac
    done
}
while :; do
    if [ -f "$REQUEST" ]; then
        read -r MINUTES < "$REQUEST" || MINUTES=20
        case "$MINUTES" in ''|*[!0-9]*) MINUTES=20 ;; esac
        [ "$MINUTES" -gt 60 ] && MINUTES=60
        rm -f "$REQUEST"
        if mkdir -p "$DIR" && chmod 700 "$DIR"; then
            OUT=$DIR/cluster-baseline.log
            echo "cluster baseline v124 start $(date) minutes=$MINUTES" > "$OUT"
            SAMPLES=$((MINUTES * 60))
            COUNT=0
            while [ "$COUNT" -lt "$SAMPLES" ]; do
                echo "--- sample $COUNT $(date)" >> "$OUT"
                dumpsys com.hkmc.micom.cluster 2>/dev/null | keep >> "$OUT"
                if [ "$(wc -c < "$OUT")" -gt "$LIMIT" ]; then
                    echo "size limit reached $(date)" >> "$OUT"
                    break
                fi
                COUNT=$((COUNT + 1))
                sleep 1
            done
            echo "cluster baseline end $(date)" >> "$OUT"
            sync
        fi
    fi
    sleep 10
done
