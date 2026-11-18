#!/system/bin/sh
# Only our numeric navigation tag. 1 MiB x 32 files = approximately 32 MiB maximum.
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
umask 077
DIR=/data/nemesis/wireless.carplay.aa-ar-debug
mkdir -p "$DIR" || exit 1
chmod 700 "$DIR" || exit 1
echo "AR debug v112 starting $(date); bounded 32x1MiB; own EvergreenAR tag only" > "$DIR/STATUS.txt"
sync
while :; do
    logcat -v threadtime -f "$DIR/flow.log" -r 1024 -n 31 -s EvergreenAR:W '*:S' 2>> "$DIR/STATUS.txt"
    # If Android/logcat is unavailable, avoid a tight NEMESIS restart loop.
    echo "logcat stopped $(date); retry in 15s" >> "$DIR/STATUS.txt"
    [ -f "$DIR/STATUS.txt" ] && [ "$(wc -c < "$DIR/STATUS.txt")" -gt 16384 ] && echo "logcat retrying $(date)" > "$DIR/STATUS.txt"
    sleep 15
done
