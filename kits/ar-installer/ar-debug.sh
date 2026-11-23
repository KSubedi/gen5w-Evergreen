#!/system/bin/sh
# Only our numeric navigation tag. 1 MiB x 32 files = approximately 32 MiB maximum.
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
umask 077
DIR=/data/nemesis/wireless.carplay.aa-ar-debug
PARENT=$$
mkdir -p "$DIR" || exit 1
chmod 700 "$DIR" || exit 1
echo "AR debug v125 starting $(date); bounded 32x1MiB; own EvergreenAR tag only" > "$DIR/STATUS.txt"
sync

# The head unit's hotspot changes Wi-Fi channel by itself, and the phone can drop shortly
# after (owner logs, 2026-11-21). Copy those kernel events into our own tag so they line up
# with the CarPlay screen records: kernel seconds, event and number only, never a hardware
# address. Stops when the recorder stops.
recording() {
    # A recorder that was stopped can linger as a zombie until it is reaped.
    local _p _c _state _r
    [ -r "/proc/$PARENT/stat" ] && read -r _p _c _state _r < "/proc/$PARENT/stat" 2>/dev/null && [ "$_state" != Z ]
}
wifi_events() {
    local _last_s=0 _last_u=0 _line _t _s _u _e _x _n
    while recording; do
        if command -v grep >/dev/null 2>&1; then
            dmesg 2>/dev/null | grep -e ch_switch_notify -e WLC_E_DEAUTH -e WLC_E_DISASSOC -e 'AP/GO Link' > "$DIR/.wifi" 2>/dev/null
        else
            dmesg > "$DIR/.wifi" 2>/dev/null
        fi
        while IFS= read -r _line; do
            case "$_line" in
                *ch_switch_notify*) _e=ch_switch; _x="freq=${_line##*freq: }"; _x=${_x%% *} ;;
                *WLC_E_DEAUTH_IND*) _e=deauth_by_phone; _x="reason=${_line##*reason }" ;;
                *WLC_E_DEAUTH*) _e=deauth; _x="reason=${_line##*reason }" ;;
                *WLC_E_DISASSOC_IND*) _e=disassoc_by_phone; _x="reason=${_line##*reason }" ;;
                *WLC_E_DISASSOC*) _e=disassoc; _x="reason=${_line##*reason }" ;;
                *"AP/GO Link up"*) _e=ap_up; _x="" ;;
                *"AP/GO Link down"*) _e=ap_down; _x="" ;;
                *) continue ;;
            esac
            _x=${_x%% *}
            case "$_x" in *[!a-z0-9=]*) _x="" ;; esac
            _t=${_line#*\[}; _t=${_t%%\]*}; _t=${_t##* }
            _s=${_t%%.*}; _u=${_t#*.}
            while :; do case "$_u" in 0?*) _u=${_u#0} ;; *) break ;; esac; done
            case "$_s$_u" in ''|*[!0-9]*) continue ;; esac
            case "$_t" in *[!0-9.]*) continue ;; esac
            [ "$_s" -gt "$_last_s" ] || { [ "$_s" -eq "$_last_s" ] && [ "$_u" -gt "$_last_u" ]; } || continue
            _last_s=$_s; _last_u=$_u
            log -p w -t EvergreenAR "v125 wifi kernel_s=$_t event=$_e${_x:+ $_x}"
        done < "$DIR/.wifi"
        rm -f "$DIR/.wifi"
        _n=0
        while [ "$_n" -lt 15 ] && recording; do sleep 1; _n=$((_n + 1)); done
    done
}
wifi_events < /dev/null > /dev/null 2>&1 &

while :; do
    logcat -v threadtime -f "$DIR/flow.log" -r 1024 -n 31 -s EvergreenAR:W '*:S' 2>> "$DIR/STATUS.txt"
    # If Android/logcat is unavailable, avoid a tight NEMESIS restart loop.
    echo "logcat stopped $(date); retry in 15s" >> "$DIR/STATUS.txt"
    [ -f "$DIR/STATUS.txt" ] && [ "$(wc -c < "$DIR/STATUS.txt")" -gt 16384 ] && echo "logcat retrying $(date)" > "$DIR/STATUS.txt"
    sleep 15
done
