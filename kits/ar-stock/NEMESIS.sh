#!/system/bin/sh
# Read-only US EV6 260507 stock collector. All writes go to this USB folder.
# Test root is only for host fixtures; empty on a car.
SOURCE=${EVERGREEN_AR_SOURCE_ROOT:-}
[ -s expected-stock.txt ] || { echo "Missing expected-stock.txt"; exit 1; }
if [ -z "$SOURCE" ]; then
    export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
fi
PRODUCT=$(getprop ro.build.product)
case "$PRODUCT" in CVEV.USA.*.260507) ;; *) echo "Wrong car/firmware: $PRODUCT"; exit 1 ;; esac
OUT=$(pwd)/EV6-AR-STOCK
# Never delete/overwrite an earlier collection or follow an existing output link.
if [ -e "$OUT" ] || [ -L "$OUT" ]; then
    echo "EV6-AR-STOCK already exists. Save it and remove it from this USB drive before rerunning."
    exit 1
fi
mkdir "$OUT" || exit 1
mkdir -p "$OUT/stock/app" "$OUT/stock/lib" "$OUT/stock/framework" || exit 1
REPORT=$OUT/report.txt
echo "product=$PRODUCT" > "$REPORT"
echo "Firmware files only. No keys, logs, account data or phone data collected." >> "$REPORT"
failed=0
while read -r name want; do
    case "$name" in
        HKMC_DiPO.apk|HKMC_DiPO.odex|HKMC_GPM.apk|HKMC_GPM.odex|HKMC_Navi_ExSL.apk|HKMC_Navi_ExSL.odex|Iap2ServerApp.apk|Iap2ServerApp.odex) dir=app ;;
        libautoreceiver_infobank.so) dir=lib ;;
        *) echo "Unexpected stock-list entry" >> "$REPORT"; exit 1 ;;
    esac
    found=0
    for file in "$SOURCE/data/nemesis/backup/projection.tbt/$name" "$SOURCE/system/$dir/$name"; do
        [ -f "$file" ] || continue
        value=$(md5 "$file" 2>/dev/null) || continue
        set -- $value
        [ "$1" = "$want" ] || continue
        cat "$file" > "$OUT/stock/$dir/$name" || exit 1
        value=$(md5 "$OUT/stock/$dir/$name") || exit 1
        set -- $value
        [ "$1" = "$want" ] || exit 1
        echo "OK $name $want" >> "$REPORT"
        found=1
        break
    done
    if [ "$found" = 0 ]; then
        echo "MISSING_STOCK $name (no exact stock checksum in live file or backup)" >> "$REPORT"
        failed=1
    fi
done < expected-stock.txt
# Framework code contains the interfaces needed to inspect the actual OEM calls.
for file in "$SOURCE"/system/framework/*.jar "$SOURCE"/system/framework/*.odex; do
    [ -f "$file" ] || continue
    cat "$file" > "$OUT/stock/framework/${file##*/}" || exit 1
done
echo "Collection finished; missing_stock=$failed" >> "$REPORT"
echo "Share only EV6-AR-STOCK from this USB drive. The car was not changed."
exit "$failed"
