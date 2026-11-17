#!/system/bin/sh
# Only our numeric navigation tag. 8 MiB x 8 files = approximately 64 MiB maximum.
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
umask 077
DIR=/data/nemesis/wireless.carplay.aa-ar-debug
mkdir -p "$DIR" || exit 1
chmod 700 "$DIR" || exit 1
echo "AR debug v104 starting $(date); bounded 8x8MiB; own EvergreenAR tag only" > "$DIR/STATUS.txt"
sync
while :; do
    logcat -v threadtime -f "$DIR/flow.log" -r 8192 -n 7 -s EvergreenAR:W '*:S' 2>> "$DIR/STATUS.txt"
    # If Android/logcat is unavailable, avoid a tight NEMESIS restart loop.
    echo "logcat stopped $(date); retry in 15s" >> "$DIR/STATUS.txt"
    [ -f "$DIR/STATUS.txt" ] && [ "$(wc -c < "$DIR/STATUS.txt")" -gt 16384 ] && echo "logcat retrying $(date)" > "$DIR/STATUS.txt"
    sleep 15
done
